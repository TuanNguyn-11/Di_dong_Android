package com.fallguard.monitor;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class FallAiInstrumentedTest {
    @Test public void exactGoldenOutputsAndProbability() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (FallAiEngine ai = new FallAiEngine(context.getAssets())) {
            assertEquals(10, ai.verifyGolden(context.getAssets()));
        }
    }

    @Test public void preprocessingUsesEvenRoundingClipsAndRejectsInvalidSamples() {
        assertEquals(-1, FallAiEngine.quantize(0,0,1));
        assertEquals(-128, FallAiEngine.quantize(-100,0,1));
        assertEquals(126, FallAiEngine.quantize(100,0,1));
        assertEquals(-1, FallAiEngine.quantize(0.5 * FallAiEngine.INPUT_SCALE,0,1));
        assertEquals(1, FallAiEngine.quantize(1.5 * FallAiEngine.INPUT_SCALE,0,1));
        assertEquals(-3, FallAiEngine.quantize(-1.5 * FallAiEngine.INPUT_SCALE,0,1));
        try { FallAiEngine.quantize(Double.NaN,0,1); fail("NaN accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void decisionRequiresTwoConsecutiveAndResets() {
        FallDecision decision = new FallDecision();
        assertFalse(decision.accept(0.7));
        assertTrue(decision.accept(0.8));
        assertFalse(decision.accept(0.69));
        assertFalse(decision.accept(0.99));
        decision.reset();
        assertFalse(decision.accept(0.99));
        assertTrue(decision.accept(0.99));
    }

    @Test public void alignsIndependentTimestampsAndDropsGaps() {
        List<Long> times = new ArrayList<>();
        List<float[]> samples = new ArrayList<>();
        int[] gaps = {0};
        ImuSynchronizer sync = new ImuSynchronizer(new ImuSynchronizer.Sink() {
            public void sample(long time, float[] v) { times.add(time); samples.add(v); }
            public void gap() { gaps[0]++; }
        });
        for (int i=0; i<8; i++) {
            long t = 1_000_000_000L + i * 10_000_000L;
            sync.add(true,t,i,0,0);
            sync.add(false,t+2_000_000L,i+0.2f,0,0);
        }
        assertEquals(7, samples.size());
        for (int i=0; i<samples.size(); i++) {
            assertEquals(i+0.2f,samples.get(i)[0],0.0001f);
            assertEquals(i+0.2f,samples.get(i)[3],0.0001f);
            if(i>0) assertEquals(10_000_000L,(long) times.get(i)-times.get(i-1));
        }
        int before = samples.size();
        sync.add(true,2_000_000_000L,0,0,0);
        assertEquals(1,gaps[0]);
        assertEquals(before,samples.size());
        sync.add(false,2_000_000_000L,Float.NaN,0,0);
        assertEquals(2,gaps[0]);
    }

    @Test public void physicalWindowAndRingOrderMatchGolden() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        org.json.JSONObject spec = new org.json.JSONObject(new String(FallAiEngine.read(context.getAssets(),"models/dilated_aug_s0_input_spec.json"),java.nio.charset.StandardCharsets.UTF_8));
        org.json.JSONArray vectors = new org.json.JSONObject(new String(FallAiEngine.read(context.getAssets(),"models/golden_inputs_synthetic.json"),java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("vectors");
        try(FallAiEngine ai = new FallAiEngine(context.getAssets())) {
            for(int v=0;v<vectors.length();v++) {
                org.json.JSONObject golden = vectors.getJSONObject(v);
                float[][] ring = new float[50][6];
                for(int t=0;t<50;t++) for(int c=0;c<6;c++) {
                    int q=golden.getJSONArray("input_int8").getJSONArray(t).getInt(c);
                    ring[(t+17)%50][c]=(float)((q+1)*FallAiEngine.INPUT_SCALE*spec.getJSONArray("std").getDouble(c)+spec.getJSONArray("mean").getDouble(c));
                }
                assertEquals(golden.getDouble("expected_p_fall"),ai.predict(ring,17),1e-7);
            }
        }
    }
}
