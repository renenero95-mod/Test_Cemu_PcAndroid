#pragma once
// Tiny, dependency-free, bounds-checked multilayer perceptron for ADVISORY models inside Cemu.
//
// Design rules (so that a wrong prediction can never become a wrong emulation):
//  - bounded activations (tanh) + clamped inputs + weights limited to +-kMaxAbsWeight => outputs are always bounded
//  - every float check uses bit patterns, so it keeps working even if the project is ever built with -ffast-math
//  - no heap allocation, fixed maximum size, no exceptions
//  - weight blobs are versioned, shape-checked and CRC32-protected; a bad blob never modifies the live model
#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

namespace Neural
{
	constexpr int kMaxLayers = 3;        // weight layers (hidden + output)
	constexpr int kMaxWidth = 32;
	constexpr float kMaxAbsWeight = 40.0f;
	constexpr float kInputClamp = 8.0f;
	constexpr uint32_t kBlobMagic = 0x574D4E43u; // "CNMW"
	constexpr uint32_t kBlobVersion = 1;

	static_assert(sizeof(float) == 4, "float must be 32 bit");
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "weight blobs are little-endian only"
#endif

	// finite check on the bit pattern (exponent != all ones); immune to -ffast-math / -ffinite-math-only
	inline bool IsFiniteF(float x)
	{
		uint32_t u;
		std::memcpy(&u, &x, sizeof(u));
		return (u & 0x7F800000u) != 0x7F800000u;
	}

	inline float SanitizeFloat(float x, float lo, float hi, float fallback)
	{
		if (!IsFiniteF(x))
			return fallback;
		return x < lo ? lo : (x > hi ? hi : x);
	}

	inline uint32_t Crc32(const uint8_t* data, size_t size)
	{
		uint32_t crc = 0xFFFFFFFFu;
		for (size_t i = 0; i < size; i++)
		{
			crc ^= data[i];
			for (int b = 0; b < 8; b++)
				crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
		}
		return ~crc;
	}

	// deterministic PRNG (xorshift64*), used for weight init and tests
	struct Rng
	{
		uint64_t s;
		explicit Rng(uint64_t seed = 88172645463325252ull) : s(seed ? seed : 88172645463325252ull) {}
		uint64_t NextU64() { s ^= s >> 12; s ^= s << 25; s ^= s >> 27; return s * 2685821657736338717ull; }
		float NextFloat() { return (float)((NextU64() >> 40) * (1.0 / 16777216.0)); } // [0,1)
		float NextRange(float lo, float hi) { return lo + (hi - lo) * NextFloat(); }
	};

	struct MLP
	{
		int nLayers = 0;
		int dims[kMaxLayers + 1] = {};
		float W[kMaxLayers][kMaxWidth * kMaxWidth] = {}; // W[l][o * dims[l] + i]
		float B[kMaxLayers][kMaxWidth] = {};

		static bool ValidShape(const int* d, int layers)
		{
			if (layers < 1 || layers > kMaxLayers)
				return false;
			for (int i = 0; i <= layers; i++)
				if (d[i] < 1 || d[i] > kMaxWidth)
					return false;
			return true;
		}

		bool Init(const int* d, int layers)
		{
			if (!ValidShape(d, layers))
				return false;
			*this = MLP();
			nLayers = layers;
			for (int i = 0; i <= layers; i++)
				dims[i] = d[i];
			return true;
		}

		void InitRandom(uint64_t seed)
		{
			Rng rng(seed);
			for (int l = 0; l < nLayers; l++)
			{
				const float limit = std::sqrt(6.0f / (float)(dims[l] + dims[l + 1])); // Xavier uniform
				for (int k = 0; k < dims[l] * dims[l + 1]; k++)
					W[l][k] = rng.NextRange(-limit, limit);
				for (int o = 0; o < dims[l + 1]; o++)
					B[l][o] = 0.0f;
			}
		}

		int InputDim() const { return dims[0]; }
		int OutputDim() const { return dims[nLayers]; }

		size_t ParamCount() const
		{
			size_t n = 0;
			for (int l = 0; l < nLayers; l++)
				n += (size_t)dims[l] * dims[l + 1] + dims[l + 1];
			return n;
		}

		bool WeightsValid() const
		{
			for (int l = 0; l < nLayers; l++)
			{
				for (int k = 0; k < dims[l] * dims[l + 1]; k++)
					if (!IsFiniteF(W[l][k]) || std::fabs(W[l][k]) > kMaxAbsWeight)
						return false;
				for (int o = 0; o < dims[l + 1]; o++)
					if (!IsFiniteF(B[l][o]) || std::fabs(B[l][o]) > kMaxAbsWeight)
						return false;
			}
			return true;
		}

		void ClampWeights()
		{
			for (int l = 0; l < nLayers; l++)
			{
				for (int k = 0; k < dims[l] * dims[l + 1]; k++)
					W[l][k] = SanitizeFloat(W[l][k], -kMaxAbsWeight, kMaxAbsWeight, 0.0f);
				for (int o = 0; o < dims[l + 1]; o++)
					B[l][o] = SanitizeFloat(B[l][o], -kMaxAbsWeight, kMaxAbsWeight, 0.0f);
			}
		}

		// inputs: dims[0] floats (anything, even NaN: sanitized), outputs: dims[nLayers] floats
		void Forward(const float* in, float* out) const
		{
			if (nLayers == 0)
				return;
			float bufA[kMaxWidth], bufB[kMaxWidth];
			float* cur = bufA;
			float* nxt = bufB;
			for (int i = 0; i < dims[0]; i++)
				cur[i] = SanitizeFloat(in[i], -kInputClamp, kInputClamp, 0.0f);
			for (int l = 0; l < nLayers; l++)
			{
				const int nin = dims[l], nout = dims[l + 1];
				const bool last = (l == nLayers - 1);
				for (int o = 0; o < nout; o++)
				{
					float s = B[l][o];
					const float* w = &W[l][o * nin];
					for (int i = 0; i < nin; i++)
						s += w[i] * cur[i];
					nxt[o] = last ? s : std::tanh(s);
				}
				std::swap(cur, nxt);
			}
			for (int o = 0; o < dims[nLayers]; o++)
				out[o] = cur[o];
		}

		// One SGD step with Huber loss, global gradient-norm clipping and weight decay.
		// Returns the loss (finite, or a large constant if the step was rejected).
		float TrainStep(const float* in, const float* target, float lr, float huberDelta, float clipNorm, float weightDecay)
		{
			if (nLayers == 0)
				return 0.0f;
			float act[kMaxLayers + 1][kMaxWidth];
			for (int i = 0; i < dims[0]; i++)
				act[0][i] = SanitizeFloat(in[i], -kInputClamp, kInputClamp, 0.0f);
			for (int l = 0; l < nLayers; l++)
			{
				const int nin = dims[l], nout = dims[l + 1];
				const bool last = (l == nLayers - 1);
				for (int o = 0; o < nout; o++)
				{
					float s = B[l][o];
					const float* w = &W[l][o * nin];
					for (int i = 0; i < nin; i++)
						s += w[i] * act[l][i];
					act[l + 1][o] = last ? s : std::tanh(s);
				}
			}
			// output delta (Huber)
			float delta[kMaxLayers + 1][kMaxWidth];
			float loss = 0.0f;
			for (int o = 0; o < dims[nLayers]; o++)
			{
				const float t = SanitizeFloat(target[o], -1000.0f, 1000.0f, 0.0f);
				const float e = act[nLayers][o] - t;
				const float ae = std::fabs(e);
				if (ae <= huberDelta)
				{
					loss += 0.5f * e * e;
					delta[nLayers][o] = e;
				}
				else
				{
					loss += huberDelta * (ae - 0.5f * huberDelta);
					delta[nLayers][o] = e > 0 ? huberDelta : -huberDelta;
				}
			}
			if (!IsFiniteF(loss))
				return 1.0e6f;
			// gradients
			static_assert(sizeof(float) * kMaxLayers * kMaxWidth * kMaxWidth <= 16384, "gradient buffer should stay small");
			float gW[kMaxLayers][kMaxWidth * kMaxWidth];
			float gB[kMaxLayers][kMaxWidth];
			float sumSq = 0.0f;
			for (int l = nLayers - 1; l >= 0; l--)
			{
				const int nin = dims[l], nout = dims[l + 1];
				for (int o = 0; o < nout; o++)
				{
					gB[l][o] = delta[l + 1][o];
					sumSq += gB[l][o] * gB[l][o];
					for (int i = 0; i < nin; i++)
					{
						const float g = delta[l + 1][o] * act[l][i];
						gW[l][o * nin + i] = g;
						sumSq += g * g;
					}
				}
				if (l > 0)
				{
					for (int i = 0; i < nin; i++)
					{
						float s = 0.0f;
						for (int o = 0; o < nout; o++)
							s += W[l][o * nin + i] * delta[l + 1][o];
						delta[l][i] = s * (1.0f - act[l][i] * act[l][i]); // tanh'
					}
				}
			}
			if (!IsFiniteF(sumSq))
				return 1.0e6f;
			const float norm = std::sqrt(sumSq);
			const float scale = (clipNorm > 0.0f && norm > clipNorm) ? clipNorm / norm : 1.0f;
			for (int l = 0; l < nLayers; l++)
			{
				for (int k = 0; k < dims[l] * dims[l + 1]; k++)
					W[l][k] -= lr * (gW[l][k] * scale + weightDecay * W[l][k]);
				for (int o = 0; o < dims[l + 1]; o++)
					B[l][o] -= lr * gB[l][o] * scale;
			}
			ClampWeights();
			return loss;
		}

		// ---- serialization -------------------------------------------------------------
		// layout: magic, version, modelId, nLayers, dims[nLayers+1], (W,B) per layer, crc32 of everything before
		size_t SerializedSize() const
		{
			return sizeof(uint32_t) * (4 + (size_t)(nLayers + 1)) + sizeof(float) * ParamCount() + sizeof(uint32_t);
		}

		bool Serialize(uint8_t* buf, size_t cap, uint32_t modelId) const
		{
			if (nLayers == 0 || cap < SerializedSize())
				return false;
			size_t p = 0;
			auto put32 = [&](uint32_t v) { std::memcpy(buf + p, &v, 4); p += 4; };
			put32(kBlobMagic); put32(kBlobVersion); put32(modelId); put32((uint32_t)nLayers);
			for (int i = 0; i <= nLayers; i++)
				put32((uint32_t)dims[i]);
			for (int l = 0; l < nLayers; l++)
			{
				const size_t nw = (size_t)dims[l] * dims[l + 1];
				std::memcpy(buf + p, W[l], nw * 4); p += nw * 4;
				std::memcpy(buf + p, B[l], (size_t)dims[l + 1] * 4); p += (size_t)dims[l + 1] * 4;
			}
			put32(Crc32(buf, p));
			return true;
		}

		// Loads into *this only if EVERYTHING checks out; otherwise *this is left untouched.
		bool Deserialize(const uint8_t* data, size_t size, uint32_t expectedModelId, const int* expectedDims, int expectedLayers)
		{
			if (data == nullptr || size < 4 * sizeof(uint32_t) + 4)
				return false;
			size_t p = 0;
			auto get32 = [&](uint32_t& v) { std::memcpy(&v, data + p, 4); p += 4; };
			uint32_t magic, version, modelId, layers;
			get32(magic); get32(version); get32(modelId); get32(layers);
			if (magic != kBlobMagic || version != kBlobVersion || modelId != expectedModelId)
				return false;
			if (layers != (uint32_t)expectedLayers || layers < 1 || layers > (uint32_t)kMaxLayers)
				return false;
			const size_t headerBytes = sizeof(uint32_t) * (4 + (size_t)layers + 1);
			if (size < headerBytes + 4)
				return false;
			int d[kMaxLayers + 1];
			for (uint32_t i = 0; i <= layers; i++)
			{
				uint32_t v;
				get32(v);
				if (v < 1 || v > (uint32_t)kMaxWidth || (int)v != expectedDims[i])
					return false;
				d[i] = (int)v;
			}
			MLP tmp;
			if (!tmp.Init(d, (int)layers))
				return false;
			if (size != tmp.SerializedSize())
				return false;
			uint32_t crcStored;
			std::memcpy(&crcStored, data + size - 4, 4);
			if (Crc32(data, size - 4) != crcStored)
				return false;
			for (int l = 0; l < tmp.nLayers; l++)
			{
				const size_t nw = (size_t)tmp.dims[l] * tmp.dims[l + 1];
				std::memcpy(tmp.W[l], data + p, nw * 4); p += nw * 4;
				std::memcpy(tmp.B[l], data + p, (size_t)tmp.dims[l + 1] * 4); p += (size_t)tmp.dims[l + 1] * 4;
			}
			if (!tmp.WeightsValid())
				return false;
			*this = tmp;
			return true;
		}
	};
}
