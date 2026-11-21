// Mathematical correctness of the MLP, independent of any learning behaviour:
//  1) analytic gradient (what TrainStep applies) == numerical gradient from finite differences, in the quadratic and the linear Huber zone
//  2) gradient clipping and weight decay do exactly what they say
//  3) with --dump the program prints a reference case that tests/neural/numpy_reference.py recomputes independently with numpy
#include "Cafe/HW/Espresso/Recompiler/Neural/NeuralMLP.h"
#include <cstdio>
#include <cstring>
#include <vector>
using namespace Neural;

static int g_fail = 0;
#define CHECK(c) do { if(!(c)) { std::printf("  FAIL line %d: %s\n", __LINE__, #c); g_fail++; } } while(0)

static double huber(double e, double d) { const double a = std::fabs(e); return a <= d ? 0.5 * e * e : d * (a - 0.5 * d); }

// loss of the model on (x, t) computed with double accumulation on top of the float forward pass
static double lossOf(const MLP& m, const float* x, float t, double delta)
{
	float o[1]; m.Forward(x, o);
	return huber((double)o[0] - (double)t, delta);
}

static MLP makeModel(uint64_t seed, int* d, int layers)
{
	MLP m; m.Init(d, layers); m.InitRandom(seed);
	Rng r(seed + 1);
	for (int l = 0; l < m.nLayers; l++) for (int o = 0; o < m.dims[l + 1]; o++) m.B[l][o] = r.NextRange(-0.3f, 0.3f);
	return m;
}

// returns max relative error between analytic and numeric gradients
static double gradCheck(MLP m, const float* x, float t, double delta, int* checked)
{
	const float lr = 0.5f; // large on purpose: the gradient is recovered from the weight difference, a tiny step would drown in float rounding
	MLP stepped = m;
	stepped.TrainStep(x, &t, lr, (float)delta, 0.0f /* no clipping */, 0.0f /* no decay */);
	double worst = 0.0; *checked = 0;
	const float eps = 1.0e-2f;
	for (int l = 0; l < m.nLayers; l++)
	{
		for (int k = 0; k < m.dims[l] * m.dims[l + 1] + m.dims[l + 1]; k++)
		{
			const bool isBias = k >= m.dims[l] * m.dims[l + 1];
			float& w = isBias ? m.B[l][k - m.dims[l] * m.dims[l + 1]] : m.W[l][k];
			const float w0 = w;
			w = w0 + eps; const double lp = lossOf(m, x, t, delta);
			w = w0 - eps; const double lm = lossOf(m, x, t, delta);
			w = w0;
			const double numeric = (lp - lm) / (2.0 * eps);
			const float w1 = isBias ? stepped.B[l][k - m.dims[l] * m.dims[l + 1]] : stepped.W[l][k];
			const double analytic = -((double)w1 - (double)w0) / lr;
			const double denom = std::max(1e-3, std::fabs(numeric) + std::fabs(analytic));
			worst = std::max(worst, std::fabs(numeric - analytic) / denom);
			(*checked)++;
		}
	}
	return worst;
}

int main(int argc, char** argv)
{
	if (argc > 1 && !std::strcmp(argv[1], "--dump"))
	{
		int d[4] = { 5, 7, 4, 1 };
		MLP m = makeModel(77, d, 3);
		float x[5] = { 0.3f, -0.8f, 0.5f, 0.1f, 0.9f }, t = 0.4f;
		std::printf("DIMS 5 7 4 1\n");
		auto dumpW = [&](const char* tag, const MLP& mm) { for (int l = 0; l < 3; l++) { std::printf("%s W%d", tag, l); for (int k = 0; k < mm.dims[l] * mm.dims[l + 1]; k++) std::printf(" %.9g", mm.W[l][k]); std::printf("\n%s B%d", tag, l); for (int o = 0; o < mm.dims[l + 1]; o++) std::printf(" %.9g", mm.B[l][o]); std::printf("\n"); } };
		dumpW("BEFORE", m);
		std::printf("INPUT"); for (float v : x) std::printf(" %.9g", v); std::printf("\nTARGET %.9g\n", t);
		float o[1]; m.Forward(x, o); std::printf("FORWARD %.9g\n", o[0]);
		// hyper-parameters of the step: lr 0.01, huber delta 0.25 (this case is in the LINEAR zone), clip 0.5, decay 0.001
		std::printf("HYPER 0.01 0.25 0.5 0.001\n");
		m.TrainStep(x, &t, 0.01f, 0.25f, 0.5f, 0.001f);
		dumpW("AFTER", m);
		return 0;
	}

	std::printf("[MATH: gradient check]\n");
	{
		int d[4] = { 6, 8, 5, 1 };
		double worstQuad = 0, worstLin = 0; int n = 0, cnt = 0;
		for (uint64_t s = 1; s <= 40; s++)
		{
			MLP m = makeModel(s, d, 3);
			Rng r(s * 7);
			float x[6]; for (float& v : x) v = r.NextRange(-1.0f, 1.0f);
			float o[1]; m.Forward(x, o);
			// quadratic zone: target close to the output; linear zone: far away
			worstQuad = std::max(worstQuad, gradCheck(m, x, o[0] + 0.3f, 1.0, &cnt)); n += cnt;
			worstLin = std::max(worstLin, gradCheck(m, x, o[0] + 3.0f, 1.0, &cnt)); n += cnt;
		}
		std::printf("  %d weights checked; worst relative error quadratic zone %.2e, linear zone %.2e\n", n, worstQuad, worstLin);
		CHECK(worstQuad < 2e-2); CHECK(worstLin < 2e-2);
		int d2[3] = { 4, 6, 1 };
		MLP m2 = makeModel(5, d2, 2); float x2[4] = { 0.2f, -0.4f, 0.7f, 0.0f }; float o2[1]; m2.Forward(x2, o2);
		CHECK(gradCheck(m2, x2, o2[0] - 0.2f, 1.0, &cnt) < 2e-2);
	}
	std::printf("[MATH: clipping and decay]\n");
	{
		int d[3] = { 3, 4, 1 };
		MLP a = makeModel(9, d, 2); float x[3] = { 0.5f, 0.5f, -0.5f }, t = 50.0f; // far target: large gradient
		MLP free_ = a, clipped = a;
		free_.TrainStep(x, &t, 0.01f, 1.0f, 0.0f, 0.0f);
		clipped.TrainStep(x, &t, 0.01f, 1.0f, 0.5f, 0.0f);
		double dFree = 0, dClip = 0;
		for (int l = 0; l < 2; l++) for (int k = 0; k < a.dims[l] * a.dims[l + 1]; k++) { dFree += std::pow(free_.W[l][k] - a.W[l][k], 2); dClip += std::pow(clipped.W[l][k] - a.W[l][k], 2); }
		for (int l = 0; l < 2; l++) for (int o = 0; o < a.dims[l + 1]; o++) { dFree += std::pow(free_.B[l][o] - a.B[l][o], 2); dClip += std::pow(clipped.B[l][o] - a.B[l][o], 2); }
		const double stepFree = std::sqrt(dFree) / 0.01, stepClip = std::sqrt(dClip) / 0.01;
		std::printf("  gradient norm without clipping %.3f, with clip 0.5: %.3f\n", stepFree, stepClip);
		CHECK(stepFree > 0.5 + 0.1);                       // the gradient really was large
		CHECK(std::fabs(stepClip - 0.5) < 1e-3);           // and clipping brought it to exactly the limit
		// weight decay with zero error: pure shrinkage W -= lr*wd*W
		MLP b = makeModel(10, d, 2); float o[1]; b.Forward(x, o); MLP c = b;
		c.TrainStep(x, o, 0.1f, 1.0f, 0.0f, 0.5f);
		CHECK(std::fabs(c.W[0][0] - b.W[0][0] * (1.0f - 0.1f * 0.5f)) < 1e-6f);
	}
	std::printf("[MATH: bounds]\n");
	{
		int d[3] = { 3, 4, 1 };
		MLP m = makeModel(11, d, 2); float x[3] = { 8, 8, 8 }, t = 1000.0f;
		for (int i = 0; i < 5000; i++) m.TrainStep(x, &t, 5.0f, 1.0f, 0.0f, 0.0f); // absurd learning rate and target
		CHECK(m.WeightsValid());
		float big[3] = { 1e30f, -1e30f, 1e30f }, o[1]; m.Forward(big, o);
		CHECK(IsFiniteF(o[0]));
		// output magnitude is bounded by construction: |out| <= sum|w_out| + |b_out| (tanh hidden)
		float bound = std::fabs(m.B[1][0]); for (int i = 0; i < m.dims[1]; i++) bound += std::fabs(m.W[1][i]);
		CHECK(std::fabs(o[0]) <= bound + 1e-3f);
	}
	std::printf(g_fail ? "RESULT: %d FAILURE(S)\n" : "RESULT: ALL PASSED\n", g_fail);
	return g_fail ? 1 : 0;
}
