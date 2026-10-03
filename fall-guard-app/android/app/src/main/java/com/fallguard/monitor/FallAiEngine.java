package com.fallguard.monitor;

import android.content.res.AssetManager;
import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Single-thread confined, CPU INT8 inference; model contract is checked before use. */
public final class FallAiEngine implements AutoCloseable {
    public static final String SHA256 = "436a3463ee4802aa960c777775b680d3f9fc50a1c5798b0204a5c8c91dff0f11";
    static final double INPUT_SCALE = 0.0313725508749485;
    static final double OUTPUT_SCALE = 0.102272629737854;
    private final Interpreter interpreter;
    private final ByteBuffer modelBuffer;
    private final ByteBuffer input = ByteBuffer.allocateDirect(300).order(ByteOrder.nativeOrder());
    private final ByteBuffer output = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder());
    private final double[] mean = new double[6], std = new double[6];

    public FallAiEngine(AssetManager assets) throws Exception {
        byte[] model = read(assets, "models/dilated_aug_s0_int8.tflite");
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(model)) hash.append(String.format("%02x", b & 255));
        if (!SHA256.equals(hash.toString())) throw new IllegalStateException("Sai SHA-256 model");
        JSONObject spec = new JSONObject(new String(read(assets, "models/dilated_aug_s0_input_spec.json"), StandardCharsets.UTF_8));
        for (int c = 0; c < 6; c++) {
            mean[c] = spec.getJSONArray("mean").getDouble(c);
            std[c] = spec.getJSONArray("std").getDouble(c);
        }
        modelBuffer = ByteBuffer.allocateDirect(model.length).order(ByteOrder.nativeOrder());
        modelBuffer.put(model).rewind();
        interpreter = new Interpreter(modelBuffer, new Interpreter.Options().setNumThreads(1).setUseXNNPACK(true));
        try {
            if (!Arrays.equals(interpreter.getInputTensor(0).shape(), new int[]{1,50,6})
                    || !Arrays.equals(interpreter.getOutputTensor(0).shape(), new int[]{1,2})
                    || interpreter.getInputTensor(0).dataType() != DataType.INT8
                    || interpreter.getOutputTensor(0).dataType() != DataType.INT8
                    || Math.abs(interpreter.getInputTensor(0).quantizationParams().getScale() - INPUT_SCALE) > 1e-9
                    || interpreter.getInputTensor(0).quantizationParams().getZeroPoint() != -1
                    || Math.abs(interpreter.getOutputTensor(0).quantizationParams().getScale() - OUTPUT_SCALE) > 1e-9
                    || interpreter.getOutputTensor(0).quantizationParams().getZeroPoint() != -8) {
                throw new IllegalStateException("Tensor model không đúng đặc tả");
            }
        } catch (Exception e) { interpreter.close(); throw e; }
    }

    static byte[] read(AssetManager assets, String name) throws Exception {
        try (InputStream in = assets.open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) out.write(block, 0, n);
            return out.toByteArray();
        }
    }

    static byte quantize(double physical, double mean, double std) {
        if (Double.isNaN(physical) || Double.isInfinite(physical)) throw new IllegalArgumentException("Mẫu cảm biến không hữu hạn");
        double u = Math.max(-4, Math.min(4, (physical - mean) / std));
        return (byte) Math.max(-128, Math.min(127, (int) Math.rint(u / INPUT_SCALE) - 1));
    }

    public double predict(float[][] ring, int oldest) {
        input.clear();
        for (int t = 0; t < 50; t++) for (int c = 0; c < 6; c++)
            input.put(quantize(ring[(oldest + t) % 50][c], mean[c], std[c]));
        run();
        return probability(output.get(0), output.get(1));
    }

    private void run() { input.rewind(); output.clear(); interpreter.run(input, output); }

    static double probability(byte adl, byte fall) {
        return 1.0 / (1.0 + Math.exp(-((int) fall - (int) adl) * OUTPUT_SCALE));
    }

    /** Diagnostic only: vectors are synthetic, not labeled activity samples. */
    public int verifyGolden(AssetManager assets) throws Exception {
        JSONArray vectors = new JSONObject(new String(read(assets, "models/golden_inputs_synthetic.json"), StandardCharsets.UTF_8)).getJSONArray("vectors");
        for (int i = 0; i < vectors.length(); i++) {
            JSONObject vector = vectors.getJSONObject(i);
            JSONArray values = vector.getJSONArray("input_int8");
            input.clear();
            for (int t = 0; t < 50; t++) for (int c = 0; c < 6; c++) input.put((byte) values.getJSONArray(t).getInt(c));
            run();
            JSONArray expected = vector.getJSONArray("expected_output_int8");
            for (int c = 0; c < 2; c++) if (output.get(c) != expected.getInt(c))
                throw new IllegalStateException("Golden " + vector.getString("name") + ": output[" + c + "]=" + output.get(c) + ", expected=" + expected.getInt(c));
            if (Math.abs(probability(output.get(0), output.get(1)) - vector.getDouble("expected_p_fall")) > 1e-7)
                throw new IllegalStateException("Sai xác suất golden");
        }
        return vectors.length();
    }

    @Override public void close() { interpreter.close(); }
}
