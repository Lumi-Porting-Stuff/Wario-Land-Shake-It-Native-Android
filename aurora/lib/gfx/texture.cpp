#include "common.hpp"

#include "../internal.hpp"
#include "../webgpu/gpu.hpp"
#include "aurora/aurora.h"
#include "texture.hpp"
#include "texture_convert.hpp"
#include "../gx/gx_fmt.hpp"

#include <algorithm>
#include <cstdint>
#include <iterator>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>

#include <absl/container/flat_hash_map.h>
#include <fmt/format.h>
#include <magic_enum.hpp>
#include <tracy/Tracy.hpp>
#include <webgpu/webgpu_cpp.h>

namespace aurora::gfx {
using webgpu::g_device;
using webgpu::g_queue;

namespace {
Module Log("aurora::gfx");

constexpr u32 div_ceil(u32 value, u32 divisor) noexcept { return (value + divisor - 1) / divisor; }

uint32_t clamp_mip_count(uint32_t width, uint32_t height, uint32_t mips, const char* label) noexcept {
  const uint32_t maxMips = max_texture_mip_count(width, height);
  const uint32_t safeMips = std::clamp(mips, 1u, maxMips);
  if (safeMips != mips) {
    Log.warn("{}: clamping mip count {} to {} for {}x{} texture", label, mips, safeMips, width, height);
  }
  return safeMips;
}

wgpu::Extent3D physical_size(wgpu::Extent3D size, TextureFormatInfo info) {
  const uint32_t width = ((size.width + info.blockWidth - 1) / info.blockWidth) * info.blockWidth;
  const uint32_t height = ((size.height + info.blockHeight - 1) / info.blockHeight) * info.blockHeight;
  return {.width = width, .height = height, .depthOrArrayLayers = size.depthOrArrayLayers};
}

// ── The sampled-texture recycling pool ────────────────────────────────────────────────────────
//
// Creating a GPU texture costs far more than its size suggests, and on some drivers it is not even
// GPU work: on the Mali build this port runs on a phone, every image creation makes a synchronous
// binder call out to ARM's compression service to ask whether that image may be AFBC-compressed.
// A profile of a heavy scene put 7% of the game thread in texture creation and 5% of it in that
// one IPC round trip - a tenth of the frame, spent asking another process a question about images
// the engine has already drawn a thousand times.
//
// The texture caches in gx.cpp are the real answer to that and work hard at it, but any cache
// misses sometimes, and a miss should not cost a trip through the driver when an image of exactly
// the right shape was released a moment ago. So released textures are kept here, keyed by the
// shape that makes them interchangeable, and handed back instead.
//
// A recycled texture is handed back with whatever pixels it last held. Every caller writes the
// whole image before sampling it (that is what new_static_texture_2d does), so this is safe, and
// it is the reason the pool is kept to that one creation path rather than offered generally.
struct TexturePoolKey {
  uint32_t width;
  uint32_t height;
  uint32_t mips;
  wgpu::TextureFormat format;
  u32 gxFormat;

  bool operator==(const TexturePoolKey& other) const noexcept {
    return width == other.width && height == other.height && mips == other.mips && format == other.format &&
           gxFormat == other.gxFormat;
  }
};

struct TexturePoolKeyHash {
  size_t operator()(const TexturePoolKey& key) const noexcept {
    size_t hash = 1469598103934665603ull;
    for (const uint64_t part : {static_cast<uint64_t>(key.width), static_cast<uint64_t>(key.height),
                                static_cast<uint64_t>(key.mips), static_cast<uint64_t>(key.format),
                                static_cast<uint64_t>(key.gxFormat)}) {
      hash = (hash ^ part) * 1099511628211ull;
    }
    return hash;
  }
};

struct PooledTexture {
  std::unique_ptr<TextureRef> ref;
  uint32_t frame;  // when it was released, for ageing
  uint64_t bytes;
};

// Textures are released wherever the last handle to them dies, which is the render worker as often
// as the game thread, so the pool is shared state.
std::mutex g_texturePoolMutex;
absl::flat_hash_map<TexturePoolKey, std::vector<PooledTexture>, TexturePoolKeyHash> g_texturePool;
uint64_t g_texturePoolBytes = 0;
// Held memory is the price of this. 48 MiB is a few hundred of the sizes a GameCube-era game
// actually uses, which is more turnover than any one scene has, and small beside what the texture
// caches themselves hold.
constexpr uint64_t MaxTexturePoolBytes = 48ull << 20;
// Past a handful of one shape, more are a scene that will not reuse them.
constexpr size_t MaxPooledPerKey = 16;
// Ten seconds at 60 frames. A texture nobody has wanted in that long belongs to a scene that is
// over, and the memory is better given back.
constexpr uint32_t PooledTextureMaxAgeFrames = 600;

void drop_pooled_texture(std::vector<PooledTexture>& entries, size_t index) noexcept {
  g_texturePoolBytes -= entries[index].bytes;
  entries[index] = std::move(entries.back());
  entries.pop_back();
}

// Everything released longer ago than PooledTextureMaxAgeFrames, and then, if the pool is still
// over its budget, whatever was released least recently until it is not. Called with the lock held.
void trim_texture_pool_locked(uint32_t frame) noexcept {
  for (auto& [key, entries] : g_texturePool) {
    for (size_t i = entries.size(); i-- > 0;) {
      if (frame - entries[i].frame > PooledTextureMaxAgeFrames) {
        drop_pooled_texture(entries, i);
      }
    }
  }
  absl::erase_if(g_texturePool, [](const auto& item) { return item.second.empty(); });
  while (g_texturePoolBytes > MaxTexturePoolBytes) {
    auto oldestKey = g_texturePool.end();
    size_t oldestIndex = 0;
    uint32_t oldestFrame = UINT32_MAX;
    for (auto it = g_texturePool.begin(); it != g_texturePool.end(); ++it) {
      for (size_t i = 0; i < it->second.size(); ++i) {
        if (it->second[i].frame <= oldestFrame) {
          oldestFrame = it->second[i].frame;
          oldestKey = it;
          oldestIndex = i;
        }
      }
    }
    if (oldestKey == g_texturePool.end()) {
      g_texturePoolBytes = 0;  // nothing left to drop: the accounting, not the pool, was wrong
      break;
    }
    drop_pooled_texture(oldestKey->second, oldestIndex);
    if (oldestKey->second.empty()) {
      g_texturePool.erase(oldestKey);
    }
  }
}

// The deleter on every handle new_dynamic_texture_2d returns. It runs when the last handle dies,
// which is the only moment at which nothing can still be reading the texture from our side; Dawn
// keeps its own reference for as long as the GPU needs it, so a later write to a recycled texture
// is ordered behind the work that was reading it.
void recycle_or_destroy_texture(TextureRef* raw) noexcept {
  std::unique_ptr<TextureRef> ref{raw};
  if (ref == nullptr || !ref->texture || ref->isReplacement) {
    return;  // a replacement texture is owned by its index, not by us
  }
  const TexturePoolKey key{
      .width = ref->size.width,
      .height = ref->size.height,
      .mips = ref->mipCount,
      .format = ref->format,
      .gxFormat = ref->gxFormat,
  };
  const uint64_t bytes = calc_texture_size(ref->format, ref->size.width, ref->size.height, ref->mipCount);
  const uint32_t frame = current_frame();
  std::lock_guard lock{g_texturePoolMutex};
  auto& entries = g_texturePool[key];
  if (entries.size() >= MaxPooledPerKey) {
    return;  // enough of this shape already; let this one go
  }
  entries.push_back(PooledTexture{.ref = std::move(ref), .frame = frame, .bytes = bytes});
  g_texturePoolBytes += bytes;
  if (g_texturePoolBytes > MaxTexturePoolBytes) {
    trim_texture_pool_locked(frame);
  }
}

// A texture of this exact shape, or nothing.
std::unique_ptr<TextureRef> take_pooled_texture(const TexturePoolKey& key) noexcept {
  std::lock_guard lock{g_texturePoolMutex};
  const auto it = g_texturePool.find(key);
  if (it == g_texturePool.end() || it->second.empty()) {
    return nullptr;
  }
  std::unique_ptr<TextureRef> ref = std::move(it->second.back().ref);
  g_texturePoolBytes -= it->second.back().bytes;
  it->second.pop_back();
  if (it->second.empty()) {
    g_texturePool.erase(it);
  }
  return ref;
}
} // namespace

void shutdown_texture_pool() noexcept {
  std::lock_guard lock{g_texturePoolMutex};
  g_texturePool.clear();
  g_texturePoolBytes = 0;
}

TextureFormatInfo format_info(wgpu::TextureFormat format) noexcept {
  switch (format) {
    DEFAULT_FATAL("unimplemented texture format {}", magic_enum::enum_name(format));
  case wgpu::TextureFormat::R8Unorm:
    return {1, 1, 1, false};
  case wgpu::TextureFormat::RG8Unorm:
  case wgpu::TextureFormat::R16Sint:
    return {1, 1, 2, false};
  case wgpu::TextureFormat::RGBA8Unorm:
  case wgpu::TextureFormat::BGRA8Unorm:
  case wgpu::TextureFormat::R32Float:
    return {1, 1, 4, false};
  case wgpu::TextureFormat::BC1RGBAUnorm:
    return {4, 4, 8, true};
  case wgpu::TextureFormat::BC3RGBAUnorm:
  case wgpu::TextureFormat::BC5RGUnorm:
  case wgpu::TextureFormat::BC7RGBAUnorm:
    return {4, 4, 16, true};
  }
}

uint64_t calc_texture_size(wgpu::TextureFormat format, u32 width, u32 height, u32 mips) noexcept {
  const auto info = format_info(format);
  uint64_t total = 0;
  for (uint32_t mip = 0; mip < mips; ++mip) {
    const uint32_t mipWidth = std::max(width >> mip, 1u);
    const uint32_t mipHeight = std::max(height >> mip, 1u);
    const uint64_t widthBlocks = div_ceil(mipWidth, info.blockWidth);
    const uint64_t heightBlocks = div_ceil(mipHeight, info.blockHeight);
    const uint64_t mipBytes = widthBlocks * heightBlocks * info.blockSize;
    total += mipBytes;
  }
  return total;
}

TextureHandle new_static_texture_2d(uint32_t width, uint32_t height, uint32_t mips, u32 format, ArrayRef<uint8_t> data,
                                    bool tlut, const char* label) noexcept {
  ZoneScoped;

  mips = clamp_mip_count(width, height, mips, label);
  auto handle = new_dynamic_texture_2d(width, height, mips, format, label);
  auto& ref = *handle;

  ConvertedTexture converted;
  if (ref.gxFormat != InvalidTextureFormat) {
    if (tlut) {
      CHECK(ref.size.height == 1, "new_static_texture_2d[{}]: expected tlut height 1, got {}", label, ref.size.height);
      CHECK(ref.mipCount == 1, "new_static_texture_2d[{}]: expected tlut mipCount 1, got {}", label, ref.mipCount);
      converted = convert_tlut(ref.gxFormat, ref.size.width, data);
    } else {
      converted = convert_texture(ref.gxFormat, ref.size.width, ref.size.height, ref.mipCount, data);
    }
    if (!converted.data.empty()) {
      data = converted.data;
      ref.hasArbitraryMips = converted.hasArbitraryMips;
    }
  }

  // One mip's worth of bytes, from `source`. The upload mechanism is the only thing that differs
  // between the real data and the zero fill below, so both go through here.
  const auto writeMip = [&ref](uint32_t mip, const uint8_t* source, uint32_t dataSize, uint32_t bytesPerRow,
                               uint32_t heightBlocks, wgpu::Extent3D physicalSize) {
    wgpu::TexelCopyTextureInfo dstView{
        .texture = ref.texture,
        .mipLevel = mip,
    };
    if constexpr (UseTextureBuffer) {
      const auto range = push_texture_data(source, dataSize, bytesPerRow, heightBlocks);
      const wgpu::TexelCopyBufferLayout dataLayout{
          .offset = range.offset,
          .bytesPerRow = bytesPerRow,
          .rowsPerImage = heightBlocks,
      };
      g_textureUploads.emplace_back(dataLayout, std::move(dstView), physicalSize);
    } else {
      const wgpu::TexelCopyBufferLayout dataLayout{
          .bytesPerRow = bytesPerRow,
          .rowsPerImage = heightBlocks,
      };
      g_queue.WriteTexture(&dstView, source, dataSize, &dataLayout, &physicalSize);
    }
  };

  uint32_t offset = 0;
  for (uint32_t mip = 0; mip < mips; ++mip) {
    const wgpu::Extent3D mipSize{
        .width = std::max(ref.size.width >> mip, 1u),
        .height = std::max(ref.size.height >> mip, 1u),
        .depthOrArrayLayers = ref.size.depthOrArrayLayers,
    };
    const auto info = format_info(ref.format);
    const auto physicalSize = physical_size(mipSize, info);
    const uint32_t widthBlocks = physicalSize.width / info.blockWidth;
    const uint32_t heightBlocks = physicalSize.height / info.blockHeight;
    const uint32_t bytesPerRow = widthBlocks * info.blockSize;
    const uint32_t dataSize = bytesPerRow * heightBlocks * mipSize.depthOrArrayLayers;
    if (offset > data.size() || dataSize > data.size() - offset) {
      Log.warn("new_static_texture_2d[{}]: stopping at mip {}: expected at least {} bytes, got {}", label, mip,
               static_cast<size_t>(offset) + dataSize, data.size());
      // The mips that were not given data are cleared rather than left as they were. A fresh
      // texture reads as zero, but this one may have come back from the recycling pool still
      // holding the last picture written to it, and a short upload must not let that show through.
      std::vector<uint8_t> zeros;
      for (uint32_t rest = mip; rest < mips; ++rest) {
        const wgpu::Extent3D restSize{
            .width = std::max(ref.size.width >> rest, 1u),
            .height = std::max(ref.size.height >> rest, 1u),
            .depthOrArrayLayers = ref.size.depthOrArrayLayers,
        };
        const auto restPhysical = physical_size(restSize, info);
        const uint32_t restWidthBlocks = restPhysical.width / info.blockWidth;
        const uint32_t restHeightBlocks = restPhysical.height / info.blockHeight;
        const uint32_t restBytesPerRow = restWidthBlocks * info.blockSize;
        const uint32_t restSizeBytes = restBytesPerRow * restHeightBlocks * restSize.depthOrArrayLayers;
        zeros.assign(restSizeBytes, 0);
        writeMip(rest, zeros.data(), restSizeBytes, restBytesPerRow, restHeightBlocks, restPhysical);
      }
      return handle;
    }
    writeMip(mip, data.data() + offset, dataSize, bytesPerRow, heightBlocks, physicalSize);
    offset += dataSize;
  }
  if (data.size() != UINT32_MAX && offset < data.size()) {
    Log.warn("new_static_texture_2d[{}]: texture used {} bytes, but given {} bytes", label, offset, data.size());
  }
  return handle;
}

TextureHandle new_dynamic_texture_2d(uint32_t width, uint32_t height, uint32_t mips, u32 gxFormat,
                                     const char* label) noexcept {
  ZoneScopedS(3);
  mips = clamp_mip_count(width, height, mips, label);
  const auto wgpuFormat = to_wgpu(gxFormat);
  const wgpu::Extent3D size{
      .width = width,
      .height = height,
      .depthOrArrayLayers = 1,
  };

  ++g_stats.texturesBuilt;
  // An image of this shape released earlier is indistinguishable from a new one once it has been
  // written, and costs nothing to take. The fields that describe what a texture is *for*, rather
  // than what shape it is, are reset here; its label is not, so a recycled texture carries the
  // name of whatever first created it in a GPU capture.
  const TexturePoolKey poolKey{
      .width = width, .height = height, .mips = mips, .format = wgpuFormat, .gxFormat = gxFormat};
  if (std::unique_ptr<TextureRef> recycled = take_pooled_texture(poolKey); recycled != nullptr) {
    recycled->hasArbitraryMips = false;
    recycled->isReplacement = false;
    recycled->gameMarker = 0;
    ++g_stats.texturesRecycled;
    return TextureHandle{recycled.release(), &recycle_or_destroy_texture};
  }

  const wgpu::TextureDescriptor textureDescriptor{
      .label = label,
      .usage = wgpu::TextureUsage::TextureBinding | wgpu::TextureUsage::CopyDst,
      .dimension = wgpu::TextureDimension::e2D,
      .size = size,
      .format = wgpuFormat,
      .mipLevelCount = mips,
      .sampleCount = 1,
  };
  auto texture = g_device.CreateTexture(&textureDescriptor);
  const auto viewLabel = fmt::format("{} view", label);
  wgpu::TextureViewDescriptor textureViewDescriptor{
      .label = viewLabel.c_str(),
      .format = wgpuFormat,
      .dimension = wgpu::TextureViewDimension::e2D,
      .mipLevelCount = mips,
  };
  auto textureView = texture.CreateView(&textureViewDescriptor);
  return TextureHandle{new TextureRef(std::move(texture), std::move(textureView), wgpu::TextureView{}, size,
                                      wgpuFormat, mips, gxFormat),
                       &recycle_or_destroy_texture};
}

TextureHandle new_render_texture(uint32_t width, uint32_t height, u32 gxFormat, const char* label) noexcept {
  ZoneScoped;

  const auto wgpuFormat = webgpu::g_graphicsConfig.surfaceConfiguration.format;
  const wgpu::Extent3D size{
      .width = width,
      .height = height,
      .depthOrArrayLayers = 1,
  };
  const wgpu::TextureDescriptor textureDescriptor{
      .label = label,
      .usage = wgpu::TextureUsage::TextureBinding | wgpu::TextureUsage::CopySrc | wgpu::TextureUsage::CopyDst |
               wgpu::TextureUsage::RenderAttachment,
      .dimension = wgpu::TextureDimension::e2D,
      .size = size,
      .format = wgpuFormat,
      .mipLevelCount = 1,
      .sampleCount = 1,
  };
  auto texture = g_device.CreateTexture(&textureDescriptor);

  // Create texture view for color attachments
  const auto viewLabel = fmt::format("{} view", label);
  wgpu::TextureViewDescriptor textureViewDescriptor{
      .label = viewLabel.c_str(),
      .format = wgpuFormat,
      .dimension = wgpu::TextureViewDimension::e2D,
  };
  auto attachmentTextureView = texture.CreateView(&textureViewDescriptor);
  wgpu::TextureView sampleTextureView = attachmentTextureView;
  return std::make_shared<TextureRef>(std::move(texture), std::move(sampleTextureView),
                                      std::move(attachmentTextureView), size, wgpuFormat, 1, gxFormat);
}

TextureHandle new_conv_texture(uint32_t width, uint32_t height, u32 gxFormat, const char* label) noexcept {
  ZoneScoped;

  const auto wgpuFormat = to_wgpu(gxFormat);
  const wgpu::Extent3D size{
      .width = width,
      .height = height,
      .depthOrArrayLayers = 1,
  };
  const wgpu::TextureDescriptor textureDescriptor{
      .label = label,
      .usage = wgpu::TextureUsage::TextureBinding | wgpu::TextureUsage::CopySrc |
               wgpu::TextureUsage::RenderAttachment,
      .dimension = wgpu::TextureDimension::e2D,
      .size = size,
      .format = wgpuFormat,
      .mipLevelCount = 1,
      .sampleCount = 1,
  };
  auto texture = g_device.CreateTexture(&textureDescriptor);

  // Create texture view for color attachments
  const auto viewLabel = fmt::format("{} view", label);
  wgpu::TextureViewDescriptor textureViewDescriptor{
      .label = viewLabel.c_str(),
      .format = wgpuFormat,
      .dimension = wgpu::TextureViewDimension::e2D,
  };
  auto attachmentTextureView = texture.CreateView(&textureViewDescriptor);
  wgpu::TextureView sampleTextureView = attachmentTextureView;
  return std::make_shared<TextureRef>(std::move(texture), std::move(sampleTextureView),
                                      std::move(attachmentTextureView), size, wgpuFormat, 1, gxFormat);
}

void write_texture(TextureRef& ref, ArrayRef<uint8_t> data) noexcept {
  ZoneScoped;

  ConvertedTexture converted;
  if (ref.gxFormat != InvalidTextureFormat) {
    converted = convert_texture(ref.gxFormat, ref.size.width, ref.size.height, ref.mipCount, data);
    ref.hasArbitraryMips = converted.hasArbitraryMips;
    if (!converted.data.empty()) {
      data = converted.data;
    }
  }

  uint32_t offset = 0;
  for (uint32_t mip = 0; mip < ref.mipCount; ++mip) {
    const wgpu::Extent3D mipSize{
        .width = std::max(ref.size.width >> mip, 1u),
        .height = std::max(ref.size.height >> mip, 1u),
        .depthOrArrayLayers = ref.size.depthOrArrayLayers,
    };
    const auto info = format_info(ref.format);
    const auto physicalSize = physical_size(mipSize, info);
    const uint32_t widthBlocks = physicalSize.width / info.blockWidth;
    const uint32_t heightBlocks = physicalSize.height / info.blockHeight;
    const uint32_t bytesPerRow = widthBlocks * info.blockSize;
    const uint32_t dataSize = bytesPerRow * heightBlocks * mipSize.depthOrArrayLayers;
    if (offset > data.size() || dataSize > data.size() - offset) {
      Log.warn("write_texture: stopping at mip {}: expected at least {} bytes, got {}", mip,
               static_cast<size_t>(offset) + dataSize, data.size());
      return;
    }
    const wgpu::TexelCopyTextureInfo dstView{
        .texture = ref.texture,
        .mipLevel = mip,
    };
    if constexpr (UseTextureBuffer) {
      const auto range = push_texture_data(data.data() + offset, dataSize, bytesPerRow, heightBlocks);
      const wgpu::TexelCopyBufferLayout dataLayout{
          .offset = range.offset,
          .bytesPerRow = bytesPerRow,
          .rowsPerImage = heightBlocks,
      };
      g_textureUploads.emplace_back(dataLayout, std::move(dstView), physicalSize);
    } else {
      const wgpu::TexelCopyBufferLayout dataLayout{
          .bytesPerRow = bytesPerRow,
          .rowsPerImage = heightBlocks,
      };
      g_queue.WriteTexture(&dstView, data.data() + offset, dataSize, &dataLayout, &physicalSize);
    }
    offset += dataSize;
  }
  if (data.size() != UINT32_MAX && offset < data.size()) {
    Log.warn("write_texture: texture used {} bytes, but given {} bytes", offset, data.size());
  }
}
} // namespace aurora::gfx
