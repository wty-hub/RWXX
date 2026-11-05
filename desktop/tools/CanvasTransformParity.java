import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.jar.JarFile;

/**
 * Correctness only: compare frozen old and candidate transforms in isolated class loaders.
 * No game boot, native backend, GPU, timing claims, or source/reference reimplementation.
 * Usage: CanvasTransformParity [--same-baseline-control] baseline-core.jar candidate-core.jar kotlin-stdlib.jar report.json [random-cases]
 * The explicit control loads the same pinned baseline twice. Non-NaN bits remain exact;
 * NaN classification must match, while payload/sign differences are reported separately.
 */
public final class CanvasTransformParity {
    private static final String OLD_SHA = "3e2b1400762370437922748b5c729cd8d919da1be30eb325c40ff1ad15dcd401";
    private static final String CLASS = "io.github.rwx.render.canvas.KoolCanvasTransform";
    private static final String ENTRY = CLASS.replace('.', '/') + ".class";
    private static final long SEED = 0x5ca1eL;
    // Actual identical-jar control: 52 NaN payload/sign differences, no other value/identity differences.
    private static final String NAN_CONTROL_SHA = "0704be838258f653cbded41c2d752b5e7648bb07ab8799caf245d243997a1c16";
    private static final int[] EDGE = {
        0, 0x80000000, 0x3f800000, 0xbf800000, 0x40000000, 0xc0000000,
        0x3f000000, 0xbf000000, 1, 0x80000001, 0x007fffff, 0x807fffff,
        0x00800000, 0x80800000, 0x7f7fffff, 0xff7fffff, 0x7f800000, 0xff800000,
        0x7fc00000, 0xffc00000, 0x7f800001, 0xff800001, 0x7fc12345, 0xffc54321
    };
    private static final String[] FIELDS = {"ScaleX", "SkewY", "SkewX", "ScaleY", "TranslateX", "TranslateY"};

    private static final class Binding implements AutoCloseable {
        final URLClassLoader loader;
        final Class<?> type;
        final Constructor<?> constructor;
        final Method scale, rotate;
        final Method[] getters = new Method[6];

        Binding(Path jar, Path stdlib) throws Exception {
            loader = new URLClassLoader(new URL[]{jar.toUri().toURL(), stdlib.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            type = Class.forName(CLASS, true, loader);
            if (type.getClassLoader() != loader) throw new IllegalStateException("Transform escaped isolated loader");
            constructor = type.getConstructor(float.class, float.class, float.class, float.class, float.class, float.class);
            scale = type.getMethod("scale", float.class, float.class, float.class, float.class);
            rotate = type.getMethod("rotate", float.class, float.class, float.class);
            for (int i = 0; i < getters.length; i++) getters[i] = type.getMethod("get" + FIELDS[i]);
        }

        Object instance(float[] values) throws Exception {
            return constructor.newInstance(values[0], values[1], values[2], values[3], values[4], values[5]);
        }

        int[] bits(Object value) throws Exception {
            int[] result = new int[6];
            for (int i = 0; i < result.length; i++) result[i] = Float.floatToRawIntBits((Float) getters[i].invoke(value));
            return result;
        }

        @Override public void close() throws Exception { loader.close(); }
    }

    private static final class Results {
        long cases, scaleCalls, rotateCalls, zeroRotationIdentityChecks, scaleObjectIdentityChecks;
        long scaleMismatches, rotateMismatches, identityMismatches;
        long scaleValueMismatches, rotateValueMismatches;
        long bothNaNDifferentRawBits, nonNaNRawBitMismatches, nanClassificationMismatches;
        final List<String> examples = new ArrayList<>();
        final List<String> compatibilityExamples = new ArrayList<>();

        void compare(Binding old, Binding candidate, String context, float[] input,
                     float sx, float sy, float degrees, float px, float py) throws Exception {
            Object a = old.instance(input), b = candidate.instance(input);
            Object scaledA = old.scale.invoke(a, sx, sy, px, py), scaledB = candidate.scale.invoke(b, sx, sy, px, py);
            int[] sa = old.bits(scaledA), sb = candidate.bits(scaledB);
            cases++; scaleCalls++; scaleObjectIdentityChecks++;
            if (scaledA == a || scaledB == b) identityMismatches++;
            if (!Arrays.equals(sa, sb)) {
                scaleMismatches++;
                boolean incompatible = classifyMismatchedComponents(sa, sb);
                if (incompatible) scaleValueMismatches++;
                example("scale", context, input, new float[]{sx, sy, px, py}, sa, sb, incompatible);
            }
            Object rotatedA = old.rotate.invoke(a, degrees, px, py), rotatedB = candidate.rotate.invoke(b, degrees, px, py);
            int[] ra = old.bits(rotatedA), rb = candidate.bits(rotatedB);
            rotateCalls++;
            if (!Arrays.equals(ra, rb)) {
                rotateMismatches++;
                boolean incompatible = classifyMismatchedComponents(ra, rb);
                if (incompatible) rotateValueMismatches++;
                example("rotate", context, input, new float[]{degrees, px, py}, ra, rb, incompatible);
            }
            if (degrees == 0f) {
                zeroRotationIdentityChecks++;
                if (rotatedA != a || rotatedB != b) identityMismatches++;
            }
        }

        boolean classifyMismatchedComponents(int[] old, int[] candidate) {
            boolean incompatible = false;
            for (int i = 0; i < old.length; i++) {
                if (old[i] == candidate[i]) continue;
                boolean oldNaN = Float.isNaN(Float.intBitsToFloat(old[i]));
                boolean candidateNaN = Float.isNaN(Float.intBitsToFloat(candidate[i]));
                if (oldNaN && candidateNaN) bothNaNDifferentRawBits++;
                else if (oldNaN != candidateNaN) { nanClassificationMismatches++; incompatible = true; }
                else { nonNaNRawBitMismatches++; incompatible = true; }
            }
            return incompatible;
        }

        void example(String operation, String context, float[] input, float[] arguments, int[] old, int[] candidate, boolean incompatible) {
            if (examples.size() >= 20 && (!incompatible || compatibilityExamples.size() >= 20)) return;
            String value = "{\"operation\":" + quote(operation) + ",\"context\":" + quote(context)
                + ",\"inputRawBits\":" + rawBits(input) + ",\"argumentRawBits\":" + rawBits(arguments)
                + ",\"baselineRawBits\":" + rawBits(old) + ",\"candidateRawBits\":" + rawBits(candidate)
                + ",\"countsAsCompatibilityFailure\":" + incompatible + "}";
            if (examples.size() < 20) examples.add(value);
            if (incompatible && compatibilityExamples.size() < 20) compatibilityExamples.add(value);
        }
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '"') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static String rawBits(float[] values) {
        int[] bits = new int[values.length];
        for (int i = 0; i < bits.length; i++) bits[i] = Float.floatToRawIntBits(values[i]);
        return rawBits(bits);
    }

    private static String rawBits(int[] values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i != 0) out.append(',');
            out.append('"').append(String.format("%08x", values[i])).append('"');
        }
        return out.append(']').toString();
    }

    private static String sha(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String classSha(Path jar) throws Exception {
        try (JarFile file = new JarFile(jar.toFile()); InputStream stream = file.getInputStream(file.getJarEntry(ENTRY))) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }
    }

    private static float edge(int index) { return Float.intBitsToFloat(EDGE[Math.floorMod(index, EDGE.length)]); }
    private static float rawScalar(Random random) {
        return Float.intBitsToFloat(random.nextInt(4) == 0 ? EDGE[random.nextInt(EDGE.length)] : random.nextInt());
    }

    public static void main(String[] args) throws Exception {
        boolean sameBaselineControl = args.length > 0 && args[0].equals("--same-baseline-control");
        if (sameBaselineControl) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length < 4 || args.length > 5)
            throw new IllegalArgumentException("[--same-baseline-control] baseline-core.jar candidate-core.jar kotlin-stdlib.jar report.json [random-cases]");
        Path baseline = Path.of(args[0]).toAbsolutePath(), candidate = Path.of(args[1]).toAbsolutePath();
        Path stdlib = Path.of(args[2]).toAbsolutePath(), report = Path.of(args[3]).toAbsolutePath();
        int randomCases = args.length == 5 ? Integer.parseInt(args[4]) : 200_000;
        if (randomCases <= 0 || randomCases > 2_000_000) throw new IllegalArgumentException("random-cases outside1..2000000");
        String baselineSha = sha(baseline), candidateSha = sha(candidate);
        if (!baselineSha.equals(OLD_SHA)) throw new IllegalArgumentException("Frozen3e2baseline SHA mismatch");
        if (sameBaselineControl) {
            if (!baselineSha.equals(candidateSha)) throw new IllegalArgumentException("Control requires the same frozen3e2jar twice");
        } else if (baselineSha.equals(candidateSha)) throw new IllegalArgumentException("Candidate must differ frombaseline");
        String oldClassSha = classSha(baseline), newClassSha = classSha(candidate);
        if (!sameBaselineControl && oldClassSha.equals(newClassSha)) throw new IllegalArgumentException("Candidate Transform class is unchanged");
        if (Files.exists(report)) throw new IllegalArgumentException("Report exists; preserve earlier result");
        Results result = new Results();
        try (Binding old = new Binding(baseline, stdlib); Binding next = new Binding(candidate, stdlib)) {
            if (old.type == next.type || old.loader == next.loader) throw new IllegalStateException("Loaders are not isolated");
            for (int i = 0; i < EDGE.length; i++) for (int j = 0; j < EDGE.length; j++) {
                float[] input = {edge(i), edge(j), edge(i+5), edge(j+11), edge(i+j), edge(3*i+j)};
                result.compare(old, next, "IEEE-boundary-"+i+"/"+j, input, edge(i+1), edge(j+2), edge(i), edge(i+7), edge(j+13));
            }
            Random random = new Random(SEED);
            for (int i = 0; i < randomCases; i++) {
                float[] input = new float[6];
                float sx, sy, degrees, px, py;
                if ((i & 1) == 0) {
                    for (int k = 0; k < 4; k++) input[k] = (random.nextFloat()*2f-1f)*32f;
                    input[4]=(random.nextFloat()*2f-1f)*100_000f; input[5]=(random.nextFloat()*2f-1f)*100_000f;
                    sx=(random.nextFloat()*2f-1f)*8f; sy=(random.nextFloat()*2f-1f)*8f;
                    degrees=(random.nextFloat()*2f-1f)*3600f;
                    px=(random.nextFloat()*2f-1f)*100_000f; py=(random.nextFloat()*2f-1f)*100_000f;
                } else {
                    for (int k = 0; k < input.length; k++) input[k]=rawScalar(random);
                    sx=rawScalar(random); sy=rawScalar(random); degrees=rawScalar(random); px=rawScalar(random); py=rawScalar(random);
                }
                result.compare(old, next, ((i&1)==0 ? "ordinary-finite-" : "mixed-raw-bits-")+i, input, sx, sy, degrees, px, py);
            }
        }
        boolean passed=result.scaleValueMismatches==0 && result.rotateValueMismatches==0 && result.identityMismatches==0;
        String json="{\n\"schema\":\"isolated-frozen-transform-parity-v2\",\n\"passed\":"+passed
            +",\n\"comparisonMode\":"+quote(sameBaselineControl ? "same-frozen-baseline-control" : "frozen-baseline-vs-candidate")
            +",\n\"strictRawNaNBitsRequired\":false,\n\"allNonNaNRawBitsRequired\":true,\n\"matchingNaNClassificationRequired\":true"
            +",\n\"nanOracleControlReportSha256\":"+quote(NAN_CONTROL_SHA)
            +",\n\"nanOracleSpecification\":\"https://docs.oracle.com/javase/specs/jls/se25/html/jls-4.html#jls-4.2.3\""
            +",\n\"nanCopySpecification\":\"https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Float.html#intBitsToFloat(int)\""
            +",\n\"correctnessOnly\":true,\n\"gameOrGpuLaunched\":false,\n\"isolatedLoaders\":true"
            +",\n\"javaRuntime\":"+quote(System.getProperty("java.runtime.version"))
            +",\n\"baseline\":"+quote(baseline.toString())+",\n\"baselineSha256\":"+quote(baselineSha)
            +",\n\"candidate\":"+quote(candidate.toString())+",\n\"candidateSha256\":"+quote(candidateSha)
            +",\n\"stdlibSha256\":"+quote(sha(stdlib))+",\n\"baselineTransformClassSha256\":"+quote(oldClassSha)
            +",\n\"candidateTransformClassSha256\":"+quote(newClassSha)+",\n\"seed\":"+SEED
            +",\n\"randomCases\":"+randomCases+",\n\"casesIncludingBoundaries\":"+result.cases
            +",\n\"scaleCalls\":"+result.scaleCalls+",\n\"rotateCalls\":"+result.rotateCalls
            +",\n\"scaleObjectIdentityChecks\":"+result.scaleObjectIdentityChecks
            +",\n\"zeroRotationIdentityChecks\":"+result.zeroRotationIdentityChecks
            +",\n\"scaleRawBitMismatches\":"+result.scaleMismatches+",\n\"rotateRawBitMismatches\":"+result.rotateMismatches
            +",\n\"scaleValueMismatches\":"+result.scaleValueMismatches+",\n\"rotateValueMismatches\":"+result.rotateValueMismatches
            +",\n\"bothNaNDifferentRawBitComponents\":"+result.bothNaNDifferentRawBits
            +",\n\"nonNaNRawBitMismatchComponents\":"+result.nonNaNRawBitMismatches
            +",\n\"nanClassificationMismatchComponents\":"+result.nanClassificationMismatches
            +",\n\"identityMismatches\":"+result.identityMismatches+",\n\"firstMismatchExamples\":["+String.join(",",result.examples)+"]"
            +",\n\"firstCompatibilityFailureExamples\":["+String.join(",",result.compatibilityExamples)+"]\n}\n";
        Files.writeString(report,json,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println("{\"passed\":"+passed+",\"cases\":"+result.cases+",\"report\":"+quote(report.toString())+"}");
        if (!passed) throw new AssertionError("FrozenTransform parity failed; preserve report and stop");
    }
}
