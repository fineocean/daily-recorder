// Builds the app, using only the JDK: src/ (code, manifest, icon) -> dailyrecorder-<versionName>.apk (+ .sha256).
// Run from the project folder: java Build.java
//
// It does what the Android SDK's tools normally do: compiles the Java code against the stubs (the stubs file),
// converts it to classes.dex (d8), encodes the manifest and resources (aapt2), and packs and signs the APK (apksigner).
//
// Signing key: $KEYSTORE (default ~/.android-keys/release.p12), created on first run.
// Password: $KEYSTORE_PASS, else the login keyring (Linux), else you are asked. To save it in the keyring once:
//   secret-tool store --label="Android signing key" service android-signing keystore ~/.android-keys/release.p12
// Back the key up: without it you can't update the app.

import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class Build {
    public static void main(String[] args) throws Exception {
        try {
            build();
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    static void build() throws Exception {
        Path manifest = Path.of("src", "AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) throw new IllegalStateException("Run this from the project folder: java Build.java");
        Matcher version = Pattern.compile("versionName=\"([^\"]+)\"").matcher(Files.readString(manifest));
        if (!version.find()) throw new IllegalStateException("No versionName in " + manifest);
        Path apk = Path.of("dailyrecorder-" + version.group(1) + ".apk");
        String home = System.getProperty("user.home");
        Path keystore = Path.of(System.getenv().getOrDefault("KEYSTORE", home + "/.android-keys/release.p12"));

        Path build = Path.of("build"), stubs = build.resolve("stubs"), classes = build.resolve("classes"), out = build.resolve("apk");
        if (Files.exists(build)) {
            try (var walk = Files.walk(build)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        for (Path p : List.of(stubs, classes, out)) Files.createDirectories(p);

        System.out.println("Compiling Java...");
        compile(stubSources(Path.of("stubs")), "-d", stubs.toString());
        List<Path> sources;
        try (var walk = Files.walk(Path.of("src"))) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        // -XDstringConcat=inline: build strings with StringBuilder, which Android runs as is, instead of invokedynamic
        compile(JAVAC.getStandardFileManager(null, null, StandardCharsets.UTF_8).getJavaFileObjectsFromPaths(sources),
                "-XDstringConcat=inline", "-classpath", stubs.toString(), "-d", classes.toString());

        System.out.println("Converting to dex...");
        Dex.convert(classes, out.resolve("classes.dex"));
        System.out.println("Compiling manifest and resources...");
        Manifest.compile(manifest, Path.of("src"), out);

        boolean newKey = !Files.exists(keystore);
        char[] password = password(keystore, newKey);
        if (newKey) createKey(keystore, password);
        System.out.println("Packing and signing...");
        Apk.pack(out, apk, keystore, "app", password);
        // Attach the .sha256 file to a release with the APK; check a download with: sha256sum -c <file>.sha256
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(apk));
        Files.writeString(Path.of(apk + ".sha256"), HexFormat.of().formatHex(hash) + "  " + apk + "\n");
        System.out.println("Built " + apk + " (" + (Files.size(apk) + 1023) / 1024 + " KB) and " + apk + ".sha256");
    }

    static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    static void compile(Iterable<? extends JavaFileObject> sources, String... options) {
        if (JAVAC == null) throw new IllegalStateException("No Java compiler: this needs a full JDK, not just a Java runtime");
        List<String> all = new ArrayList<>(List.of("-source", "17", "-target", "17", "-Xlint:-options"));
        all.addAll(List.of(options));
        if (!JAVAC.getTask(null, null, null, all, null, sources).call()) throw new IllegalStateException("Compiling failed");
    }

    /**
     * The stubs file, cut at its "// == <path>" lines into one in-memory source file each, since Java wants each
     * public class in a file of its own name. Text above the first such line is notes and is skipped.
     */
    static List<JavaFileObject> stubSources(Path file) throws IOException {
        List<JavaFileObject> list = new ArrayList<>();
        String[] parts = Files.readString(file).split("(?m)^// == ");
        for (int i = 1; i < parts.length; i++) {
            int lineEnd = parts[i].indexOf('\n');
            String path = parts[i].substring(0, lineEnd).trim(), source = parts[i].substring(lineEnd + 1);
            list.add(new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            });
        }
        return list;
    }

    /**
     * The keystore password: $KEYSTORE_PASS, else the login keyring (Linux's secret-tool), else typed in, twice
     * for a new key, since a typo would become a password nobody knows.
     */
    static char[] password(Path keystore, boolean newKey) throws Exception {
        String env = System.getenv("KEYSTORE_PASS");
        if (env != null && !env.isEmpty()) return env.toCharArray();
        try {
            Process p = new ProcessBuilder("secret-tool", "lookup", "service", "android-signing", "keystore", keystore.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String saved = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() == 0 && !saved.isEmpty()) return saved.toCharArray();
        } catch (IOException e) {
            // no secret-tool on this system
        }
        Console console = System.console();
        if (console == null) throw new IllegalStateException("Set KEYSTORE_PASS: there's no terminal to ask for the keystore password");
        char[] typed = console.readPassword(newKey ? "Password for the new signing key: " : "Keystore password: ");
        if (newKey && !Arrays.equals(typed, console.readPassword("Same password again: "))) {
            throw new IllegalStateException("The passwords didn't match; no key was created");
        }
        return typed;
    }

    static void createKey(Path keystore, char[] password) throws Exception {
        System.out.println("Creating signing key at " + keystore + " (back this file up!)");
        Path dir = keystore.toAbsolutePath().getParent();
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                // not a Unix file system
            }
        }
        ProcessBuilder keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-alias", "app",
                "-keyalg", "RSA", "-keysize", "4096", "-validity", "10000", "-dname", "CN=app",
                "-storepass:env", "KEYSTORE_PASS", "-keypass:env", "KEYSTORE_PASS").inheritIO();
        keytool.environment().put("KEYSTORE_PASS", new String(password));
        if (keytool.start().waitFor() != 0) throw new IllegalStateException("keytool couldn't create the key");
        try {
            Files.setPosixFilePermissions(keystore, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // not a Unix file system
        }
    }

    static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Manifest and resource table (what aapt2 normally does).
     * Values: true/false -> boolean, digits -> integer, @android:type/name -> reference, anything else -> string.
     * Android's public resource IDs never change. To use a new android: attribute or @android: resource, add its
     * ID to the tables below (look it up with: aapt2 dump xmltree, or Android's res/values/public.xml).
     */
    static class Manifest {
        static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
        static final Map<String, Integer> ATTRS = Map.of(
                "theme", 0x01010000,
                "label", 0x01010001,
                "icon", 0x01010002,
                "name", 0x01010003,
                "exported", 0x01010010,
                "minSdkVersion", 0x0101020c,
                "targetSdkVersion", 0x01010270,
                "versionCode", 0x0101021b,
                "versionName", 0x0101021c,
                "allowBackup", 0x01010280);
        // Android's resources; the app's own (@drawable/...) are added from the image dir at build time.
        static final Map<String, Integer> RESOURCES = new HashMap<>(Map.of(
                "@android:style/Theme.DeviceDefault.DayNight", 0x010302e3));
        static final int DRAWABLE_IDS = 0x7f010000; // app package 0x7f, type 1 = drawable
        static final Set<String> ALWAYS_STRING = Set.of("label", "name", "versionName"); // "versionName" so "2" stays text

        static final int TYPE_REFERENCE = 0x01, TYPE_STRING = 0x03, TYPE_INT_DEC = 0x10, TYPE_BOOLEAN = 0x12;

        static final List<String> strings = new ArrayList<>();
        static final Map<String, Integer> stringIndex = new HashMap<>();

        record Attr(String ns, String name, int resId, String raw) {}

        /** Writes the binary AndroidManifest.xml, and the .png files in imageDir plus resources.arsc, into outDir. */
        static void compile(Path manifestFile, Path imageDir, Path outDir) throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = f.newDocumentBuilder().parse(manifestFile.toFile()).getDocumentElement();

            // .png files in the image dir are copied into the APK's res/drawable/ and listed in resources.arsc
            // as @drawable/<name>. Subfolders and other files (like the app's Java source) are ignored.
            List<String> keys = new ArrayList<>(), paths = new ArrayList<>();
            List<Path> images;
            try (var list = Files.list(imageDir)) {
                images = list.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".png")).sorted().toList();
            }
            if (!images.isEmpty()) {
                Files.createDirectories(outDir.resolve("res/drawable"));
                for (Path image : images) {
                    String file = image.getFileName().toString();
                    String key = file.substring(0, file.indexOf('.'));
                    RESOURCES.put("@drawable/" + key, DRAWABLE_IDS + keys.size());
                    keys.add(key);
                    paths.add("res/drawable/" + file);
                    Files.copy(image, outDir.resolve("res/drawable/" + file));
                }
            }
            if (!keys.isEmpty()) {
                Files.write(outDir.resolve("resources.arsc"), resourceTable(root.getAttribute("package"), keys, paths));
            }

            // Attribute names that carry a resource ID must come first in the string pool,
            // in the same order as the resource map.
            TreeMap<Integer, String> idNames = new TreeMap<>();
            collectIds(root, idNames);
            List<Integer> resourceMap = new ArrayList<>(idNames.keySet());
            for (String n : idNames.values()) intern(n);
            intern("android");
            intern(ANDROID_NS);

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(node(0x0100, namespaceBody()));
            writeElement(root, body);
            body.write(node(0x0101, namespaceBody()));

            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.write(stringPool(strings));
            file.write(chunk(0x0180, ints(resourceMap)));
            file.write(body.toByteArray());

            byte[] content = file.toByteArray();
            ByteBuffer out = le(8 + content.length);
            out.putShort((short) 0x0003).putShort((short) 8).putInt(8 + content.length).put(content);
            Files.write(outDir.resolve("AndroidManifest.xml"), out.array());
        }

        /**
         * resources.arsc listing drawables (keys[i] -> file paths[i], ID DRAWABLE_IDS + i) in a single "nodpi" config:
         * used on every screen as is, without scaling. Format: frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
         */
        static byte[] resourceTable(String pkg, List<String> keys, List<String> paths) {
            int n = keys.size();
            byte[] typeStrings = stringPool(List.of("drawable"));
            byte[] keyStrings = stringPool(keys);

            // Type spec: one flags word per entry (0: the same in every config)
            ByteBuffer spec = le(16 + 4 * n);
            spec.putShort((short) 0x0202).putShort((short) 16).putInt(16 + 4 * n)
                    .put((byte) 1).put((byte) 0).putShort((short) 0).putInt(n);

            // Type: header with its config, entry offsets, then entries (key index + string value pointing to the file path)
            int configSize = 64;
            ByteBuffer config = le(configSize);
            config.putInt(0, configSize).putShort(14, (short) 0xffff); // density = nodpi; everything else = any
            int headerSize = 20 + configSize, entriesStart = headerSize + 4 * n, typeSize = entriesStart + 16 * n;
            ByteBuffer type = le(typeSize);
            type.putShort((short) 0x0201).putShort((short) headerSize).putInt(typeSize)
                    .put((byte) 1).put((byte) 0).putShort((short) 0).putInt(n).putInt(entriesStart).put(config.array());
            for (int i = 0; i < n; i++) type.putInt(16 * i);
            for (int i = 0; i < n; i++) {
                type.putShort((short) 8).putShort((short) 0).putInt(i);
                type.putShort((short) 8).put((byte) 0).put((byte) TYPE_STRING).putInt(i);
            }

            // Package 0x7f: header (with the name as 128 UTF-16 units), type names, key names, spec, type
            int packageHeader = 288;
            int packageSize = packageHeader + typeStrings.length + keyStrings.length + spec.capacity() + typeSize;
            ByteBuffer p = le(packageSize);
            p.putShort((short) 0x0200).putShort((short) packageHeader).putInt(packageSize).putInt(0x7f);
            for (int i = 0; i < 128; i++) p.putChar(i < pkg.length() ? pkg.charAt(i) : 0);
            p.putInt(packageHeader).putInt(1).putInt(packageHeader + typeStrings.length).putInt(n).putInt(0);
            p.put(typeStrings).put(keyStrings).put(spec.array()).put(type.array());

            // Table: header, global string pool (the file paths), one package
            byte[] values = stringPool(paths);
            int size = 12 + values.length + packageSize;
            ByteBuffer t = le(size);
            t.putShort((short) 0x0002).putShort((short) 12).putInt(size).putInt(1).put(values).put(p.array());
            return t.array();
        }

        static void collectIds(Element e, Map<Integer, String> ids) {
            NamedNodeMap attrs = e.getAttributes();
            for (int i = 0; i < attrs.getLength(); i++) {
                Node a = attrs.item(i);
                if (ANDROID_NS.equals(a.getNamespaceURI())) ids.put(attrId(a.getLocalName()), a.getLocalName());
            }
            for (Element child : children(e)) collectIds(child, ids);
        }

        static void writeElement(Element e, OutputStream out) throws IOException {
            List<Attr> attrs = new ArrayList<>();
            NamedNodeMap nodes = e.getAttributes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node a = nodes.item(i);
                if ("http://www.w3.org/2000/xmlns/".equals(a.getNamespaceURI())) continue;
                boolean android = ANDROID_NS.equals(a.getNamespaceURI());
                String name = a.getNamespaceURI() == null ? a.getNodeName() : a.getLocalName();
                attrs.add(new Attr(android ? ANDROID_NS : null, name, android ? attrId(name) : 0, a.getNodeValue()));
            }
            // Android matches attributes by walking them in ascending resource-ID order.
            attrs.sort(Comparator.comparingInt(Attr::resId));

            ByteBuffer b = le(20 + 20 * attrs.size());
            b.putInt(-1).putInt(intern(e.getTagName()));
            b.putShort((short) 20).putShort((short) 20).putShort((short) attrs.size());
            b.putShort((short) 0).putShort((short) 0).putShort((short) 0);
            for (Attr a : attrs) {
                int[] typed = typedValue(a);
                b.putInt(a.ns == null ? -1 : intern(a.ns)).putInt(intern(a.name));
                b.putInt(typed[0] == TYPE_STRING ? intern(a.raw) : -1);
                b.putShort((short) 8).put((byte) 0).put((byte) typed[0]).putInt(typed[1]);
            }
            out.write(node(0x0102, b.array()));

            for (Element child : children(e)) writeElement(child, out);

            ByteBuffer end = le(8);
            end.putInt(-1).putInt(intern(e.getTagName()));
            out.write(node(0x0103, end.array()));
        }

        static int[] typedValue(Attr a) {
            String v = a.raw;
            if (a.ns == null || ALWAYS_STRING.contains(a.name)) return new int[] { TYPE_STRING, intern(v) };
            if (v.equals("true") || v.equals("false")) return new int[] { TYPE_BOOLEAN, v.equals("true") ? -1 : 0 };
            if (v.matches("-?\\d+")) return new int[] { TYPE_INT_DEC, Integer.parseInt(v) };
            if (v.startsWith("@")) return new int[] { TYPE_REFERENCE, lookup(RESOURCES, v) };
            return new int[] { TYPE_STRING, intern(v) };
        }

        static int attrId(String name) {
            return lookup(ATTRS, name);
        }

        static int lookup(Map<String, Integer> table, String key) {
            Integer id = table.get(key);
            if (id == null) throw new IllegalArgumentException("unknown: " + key + " (add its ID to Build.java)");
            return id;
        }

        static List<Element> children(Element e) {
            List<Element> list = new ArrayList<>();
            for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element child) list.add(child);
            return list;
        }

        static int intern(String s) {
            return stringIndex.computeIfAbsent(s, k -> { strings.add(k); return strings.size() - 1; });
        }

        static byte[] namespaceBody() {
            return le(8).putInt(intern("android")).putInt(intern(ANDROID_NS)).array();
        }

        // String pool, UTF-16 encoded.
        static byte[] stringPool(List<String> strings) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int[] offsets = new int[strings.size()];
            for (int i = 0; i < strings.size(); i++) {
                offsets[i] = data.size();
                String s = strings.get(i);
                ByteBuffer b = le(2 + s.length() * 2 + 2);
                b.putShort((short) s.length());
                for (char c : s.toCharArray()) b.putChar(c);
                b.putShort((short) 0);
                data.writeBytes(b.array());
            }
            while (data.size() % 4 != 0) data.write(0);

            int headerSize = 28;
            int stringsStart = headerSize + offsets.length * 4;
            int size = stringsStart + data.size();
            ByteBuffer b = le(size);
            b.putShort((short) 0x0001).putShort((short) headerSize).putInt(size);
            b.putInt(strings.size()).putInt(0).putInt(0).putInt(stringsStart).putInt(0);
            for (int o : offsets) b.putInt(o);
            b.put(data.toByteArray());
            return b.array();
        }

        // Generic chunk: type, 8-byte header, body.
        static byte[] chunk(int type, byte[] body) {
            return le(8 + body.length).putShort((short) type).putShort((short) 8).putInt(8 + body.length).put(body).array();
        }

        // XML tree node: type, 16-byte header (line number, no comment), body.
        static byte[] node(int type, byte[] body) {
            return le(16 + body.length).putShort((short) type).putShort((short) 16).putInt(16 + body.length)
                    .putInt(1).putInt(-1).put(body).array();
        }

        static byte[] ints(List<Integer> values) {
            ByteBuffer b = le(values.size() * 4);
            for (int v : values) b.putInt(v);
            return b.array();
        }
    }

    /**
     * APK packing and v2 signing (what zipalign and apksigner normally do).
     * .dex files and resources.arsc are stored uncompressed and 4-byte aligned so Android can map them in place
     * (required for resources.arsc); the rest is deflated.
     * Signing format: https://source.android.com/docs/security/features/apksigning/v2
     */
    static class Apk {
        static final short DOS_DATE = (1 << 5) | 1; // 1980-01-01: fixed timestamps keep builds reproducible
        static final int CHUNK = 1 << 20;
        static final int V2_BLOCK_ID = 0x7109871a;

        /** Packs the files in root into an APK at out, signed with the key under alias in the PKCS12 keystore. */
        static void pack(Path root, Path out, Path keystore, String alias, char[] password) throws Exception {
            // ZIP entries and central directory
            ByteArrayOutputStream entries = new ByteArrayOutputStream();
            ByteArrayOutputStream centralDir = new ByteArrayOutputStream();
            List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String path = root.relativize(file).toString().replace(File.separatorChar, '/');
                byte[] name = path.getBytes(StandardCharsets.UTF_8);
                byte[] data = Files.readAllBytes(file);
                boolean stored = path.endsWith(".dex") || path.equals("resources.arsc");
                byte[] body = stored ? data : deflate(data);
                CRC32 crc = new CRC32();
                crc.update(data);
                int offset = entries.size();

                // Alignment extra field (ID 0xd935, as zipalign writes it) so stored data starts on a 4-byte boundary.
                byte[] extra = new byte[0];
                if (stored) {
                    int pad = (4 - (offset + 30 + name.length + 6) % 4) % 4;
                    extra = le(6 + pad).putShort((short) 0xd935).putShort((short) (2 + pad)).putShort((short) 4).array();
                }

                entries.write(le(30).putInt(0x04034b50).putShort((short) (stored ? 10 : 20)).putShort((short) 0)
                        .putShort((short) (stored ? 0 : 8)).putShort((short) 0).putShort(DOS_DATE)
                        .putInt((int) crc.getValue()).putInt(body.length).putInt(data.length)
                        .putShort((short) name.length).putShort((short) extra.length).array());
                entries.write(name);
                entries.write(extra);
                entries.write(body);

                centralDir.write(le(46).putInt(0x02014b50).putShort((short) 20).putShort((short) (stored ? 10 : 20))
                        .putShort((short) 0).putShort((short) (stored ? 0 : 8)).putShort((short) 0).putShort(DOS_DATE)
                        .putInt((int) crc.getValue()).putInt(body.length).putInt(data.length)
                        .putShort((short) name.length).putShort((short) 0).putShort((short) 0).putShort((short) 0)
                        .putShort((short) 0).putInt(0).putInt(offset).array());
                centralDir.write(name);
            }
            byte[] zipEntries = entries.toByteArray();
            byte[] zipCentralDir = centralDir.toByteArray();
            int count = files.size();

            // Signing key
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                ks.load(in, password);
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't open " + keystore + ": wrong password?");
            }
            PrivateKey key = (PrivateKey) ks.getKey(alias, password);
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
            if (key == null || cert == null) throw new IllegalStateException("No key '" + alias + "' in " + keystore);
            int algorithm;
            String jcaAlgorithm;
            switch (key.getAlgorithm()) {
                case "RSA" -> { algorithm = 0x0103; jcaAlgorithm = "SHA256withRSA"; }
                case "EC" -> { algorithm = 0x0201; jcaAlgorithm = "SHA256withECDSA"; }
                default -> throw new IllegalArgumentException("unsupported key type: " + key.getAlgorithm());
            }

            // The digest covers the entries, central directory, and end record (with the central directory offset
            // pointing where the signing block will start, i.e. exactly as in the unsigned ZIP).
            byte[] digest = chunkedSha256(zipEntries, zipCentralDir, endRecord(count, zipCentralDir.length, zipEntries.length));

            byte[] signedData = concat(
                    lp(lp(concat(u32(algorithm), lp(digest)))),
                    lp(lp(cert.getEncoded())),
                    lp(new byte[0]));
            Signature signature = Signature.getInstance(jcaAlgorithm);
            signature.initSign(key);
            signature.update(signedData);
            byte[] signer = concat(
                    lp(signedData),
                    lp(lp(concat(u32(algorithm), lp(signature.sign())))),
                    lp(cert.getPublicKey().getEncoded()));
            byte[] v2Value = lp(lp(signer));

            byte[] pairs = concat(u64(4 + v2Value.length), u32(V2_BLOCK_ID), v2Value);
            long blockSize = pairs.length + 8 + 16;
            byte[] signingBlock = concat(u64(blockSize), pairs, u64(blockSize), "APK Sig Block 42".getBytes(StandardCharsets.US_ASCII));

            try (OutputStream file = Files.newOutputStream(out)) {
                file.write(zipEntries);
                file.write(signingBlock);
                file.write(zipCentralDir);
                file.write(endRecord(count, zipCentralDir.length, zipEntries.length + signingBlock.length));
            }
        }

        static byte[] endRecord(int count, int centralDirSize, int centralDirOffset) {
            return le(22).putInt(0x06054b50).putShort((short) 0).putShort((short) 0).putShort((short) count)
                    .putShort((short) count).putInt(centralDirSize).putInt(centralDirOffset).putShort((short) 0).array();
        }

        static byte[] chunkedSha256(byte[]... sections) throws Exception {
            ByteArrayOutputStream chunkDigests = new ByteArrayOutputStream();
            int chunks = 0;
            for (byte[] section : sections) {
                for (int off = 0; off < section.length; off += CHUNK) {
                    int len = Math.min(CHUNK, section.length - off);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    md.update((byte) 0xa5);
                    md.update(u32(len));
                    md.update(section, off, len);
                    chunkDigests.write(md.digest());
                    chunks++;
                }
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((byte) 0x5a);
            md.update(u32(chunks));
            md.update(chunkDigests.toByteArray());
            return md.digest();
        }

        static byte[] deflate(byte[] data) {
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
            deflater.end();
            return out.toByteArray();
        }

        // Length-prefixed (uint32 little-endian) byte string.
        static byte[] lp(byte[] data) {
            return concat(u32(data.length), data);
        }

        static byte[] u32(int v) {
            return le(4).putInt(v).array();
        }

        static byte[] u64(long v) {
            return le(8).putLong(v).array();
        }

        static byte[] concat(byte[]... parts) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] p : parts) out.writeBytes(p);
            return out.toByteArray();
        }
    }

    /**
     * Java .class files -> classes.dex (what d8/r8 normally do). Written for the bytecode javac makes for this app,
     * not for Java in general: anything else stops the build with "unsupported: ..." rather than making a broken app.
     *
     * Simple rather than small or fast: every Java local variable and stack slot gets its own register, values move
     * between them the way the bytecode moves them, and each lambda becomes a small generated class.
     *
     * Dex has no Java "nestmates", so a private member that another class uses (a lambda's body, called from its
     * generated class) can't stay private. Fields just lose `private`. Such methods also get a "$" added to their
     * name unless they have one, so they can't accidentally override an Android method; others stay private.
     * Format: https://source.android.com/docs/core/runtime/dex-format
     */
    static class Dex {
        static final int TEMPS = 4; // v0-v3: scratch for instructions that can only name registers v0-v15

        static final List<ClassDef> classes = new ArrayList<>();
        static final Map<String, Integer> lambdaCounts = new HashMap<>();
        static final Set<String> privateMethods = new HashSet<>(), shared = new HashSet<>(); // "owner.name(desc)"
        // Everything the code refers to; their index numbers are known only once all classes are translated
        static final Set<String> strings = new TreeSet<>(), types = new TreeSet<>();
        static final Set<Proto> protos = new HashSet<>();
        static final Set<FieldRef> fieldRefs = new HashSet<>();
        static final Set<MethodRef> methodRefs = new HashSet<>();

        record Str(String s) {}
        record Type(String desc) {}
        record FieldRef(String cls, String name, String type) {}
        record MethodRef(String cls, String name, String desc) {}
        record Proto(String ret, List<String> params) {}
        record FieldDef(FieldRef ref, int access, Object value) {}
        record MethodDef(MethodRef ref, int access, Code code) {}

        static final class ClassDef {
            final String desc, superDesc;
            final int access;
            String sourceFile; // for stack traces
            final List<String> interfaces = new ArrayList<>();
            final List<FieldDef> fields = new ArrayList<>();
            final List<MethodDef> methods = new ArrayList<>();

            ClassDef(String desc, int access, String superDesc) {
                this.desc = type(desc).desc();
                this.access = access;
                this.superDesc = superDesc == null ? null : type(superDesc).desc();
            }
        }

        static void convert(Path classesDir, Path out) throws Exception {
            List<ClassFile> files = new ArrayList<>();
            try (var walk = Files.walk(classesDir)) {
                for (Path p : walk.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    files.add(new ClassFile(Files.readAllBytes(p)));
                }
            }
            // Which private methods other classes use: called from another class, or a lambda's body
            for (ClassFile cf : files) {
                for (Method m : cf.methods) if ((m.access() & 2) != 0) privateMethods.add(key(cf.name, m.name(), m.desc()));
            }
            for (ClassFile cf : files) {
                for (int i = 1; i < cf.tag.length; i++) {
                    if ((cf.tag[i] == 10 || cf.tag[i] == 11) && !cf.className(cf.a[i]).equals(cf.name)) shared.add(key(cf.ref(i)));
                }
                for (int[] bootstrap : cf.bootstraps) {
                    for (int k = 1; k < bootstrap.length; k++) if (cf.tag[bootstrap[k]] == 15) shared.add(key(cf.ref(cf.b[bootstrap[k]])));
                }
            }
            shared.retainAll(privateMethods);
            for (ClassFile cf : files) classes.add(translate(cf));
            Files.write(out, write());
        }

        static String key(String owner, String name, String desc) {
            return owner + "." + name + desc;
        }

        static String key(Ref r) {
            return key(r.owner(), r.name(), r.desc());
        }

        /** A private method no other class uses: it stays private, and calls to it must be direct. */
        static boolean staysPrivate(Ref r) {
            return privateMethods.contains(key(r)) && !shared.contains(key(r));
        }

        /** A method's name in the dex: shared private methods without a "$" get one (see the class comment). */
        static String dexName(Ref r) {
            return shared.contains(key(r)) && !r.name().contains("$") ? r.name() + "$" : r.name();
        }

        static IllegalStateException unsupported(String what) {
            return new IllegalStateException("unsupported: " + what);
        }

        // ---- References (each call also records what the dex file must list) ----

        static Str str(String s) {
            strings.add(s);
            return new Str(s);
        }

        static Type type(String desc) {
            strings.add(desc);
            types.add(desc);
            return new Type(desc);
        }

        static FieldRef field(String cls, String name, String type) {
            type(cls);
            type(type);
            strings.add(name);
            FieldRef f = new FieldRef(cls, name, type);
            fieldRefs.add(f);
            return f;
        }

        static MethodRef method(String cls, String name, String desc) {
            type(cls);
            strings.add(name);
            proto(desc);
            MethodRef m = new MethodRef(cls, name, desc);
            methodRefs.add(m);
            return m;
        }

        static Proto proto(String desc) {
            Proto p = new Proto(ret(desc), params(desc));
            type(p.ret());
            p.params().forEach(Dex::type);
            strings.add(shorty(p));
            protos.add(p);
            return p;
        }

        // ---- Type descriptors: I, J, Ljava/lang/String;, [I, (II)V ... ----

        static String desc(String internalName) {
            return internalName.startsWith("[") ? internalName : "L" + internalName + ";";
        }

        static List<String> params(String methodDesc) {
            List<String> list = new ArrayList<>();
            for (int i = 1; methodDesc.charAt(i) != ')'; ) {
                int start = i;
                while (methodDesc.charAt(i) == '[') i++;
                i = methodDesc.charAt(i) == 'L' ? methodDesc.indexOf(';', i) + 1 : i + 1;
                list.add(methodDesc.substring(start, i));
            }
            return list;
        }

        static String ret(String methodDesc) {
            return methodDesc.substring(methodDesc.indexOf(')') + 1);
        }

        static String shorty(Proto p) {
            StringBuilder s = new StringBuilder(p.ret().length() > 1 ? "L" : p.ret());
            for (String t : p.params()) s.append(t.length() > 1 ? 'L' : t.charAt(0));
            return s.toString();
        }

        static int words(String type) {
            return type.equals("J") || type.equals("D") ? 2 : 1;
        }

        static int words(List<String> types) {
            int sum = 0;
            for (String t : types) sum += words(t);
            return sum;
        }

        /** Register kind: 'I' for 32-bit numbers (int, float, boolean ...), 'J' for long and double, 'L' for objects. */
        static char kind(String type) {
            return words(type) == 2 ? 'J' : type.length() > 1 ? 'L' : 'I';
        }

        /** Which of the get/put instruction variants a field type uses: plain, wide, object, boolean, byte, char, short. */
        static int variant(String type) {
            return switch (type.charAt(0)) {
                case 'J', 'D' -> 1;
                case 'L', '[' -> 2;
                case 'Z' -> 3;
                case 'B' -> 4;
                case 'C' -> 5;
                case 'S' -> 6;
                default -> 0;
            };
        }

        // ---- Reading .class files ----

        record Ref(String owner, String name, String desc) {}           // owner as an internal name: java/lang/String
        record Handler(int start, int end, int handler, String type) {} // type null: catches everything
        record Field(int access, String name, String desc, Object value) {}
        record Method(int access, String name, String desc, int maxStack, int maxLocals, byte[] code,
                List<Handler> handlers, List<int[]> lines) {} // lines: {bytecode offset, source line}

        /** The parts of a .class file the translation needs. */
        static final class ClassFile {
            int access;
            String name, superName, sourceFile;
            final List<String> interfaces = new ArrayList<>();
            final List<Field> fields = new ArrayList<>();
            final List<Method> methods = new ArrayList<>();
            final List<int[]> bootstraps = new ArrayList<>(); // {method handle, arguments...}
            final int[] tag, a, b;                            // constant pool: kind of entry, and up to two indexes
            final Object[] value;                             // constant pool: texts and numbers

            ClassFile(byte[] bytes) throws IOException {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
                in.skipNBytes(8); // magic number, version
                int count = in.readUnsignedShort();
                tag = new int[count];
                a = new int[count];
                b = new int[count];
                value = new Object[count];
                for (int i = 1; i < count; i++) {
                    tag[i] = in.readUnsignedByte();
                    switch (tag[i]) {
                        case 1 -> value[i] = in.readUTF();
                        case 3 -> value[i] = in.readInt();
                        case 4 -> value[i] = in.readFloat();
                        case 5 -> { value[i] = in.readLong(); i++; } // longs and doubles take two entries
                        case 6 -> { value[i] = in.readDouble(); i++; }
                        case 7, 8, 16, 19, 20 -> a[i] = in.readUnsignedShort();
                        case 15 -> { a[i] = in.readUnsignedByte(); b[i] = in.readUnsignedShort(); }
                        case 9, 10, 11, 12, 17, 18 -> { a[i] = in.readUnsignedShort(); b[i] = in.readUnsignedShort(); }
                        default -> throw new IOException("unknown constant pool entry " + tag[i]);
                    }
                }
                access = in.readUnsignedShort();
                name = className(in.readUnsignedShort());
                int superIndex = in.readUnsignedShort();
                superName = superIndex == 0 ? null : className(superIndex);
                for (int i = in.readUnsignedShort(); i > 0; i--) interfaces.add(className(in.readUnsignedShort()));
                for (int i = in.readUnsignedShort(); i > 0; i--) {
                    int flags = in.readUnsignedShort();
                    String fieldName = utf(in.readUnsignedShort()), desc = utf(in.readUnsignedShort());
                    Object constant = null;
                    for (int j = in.readUnsignedShort(); j > 0; j--) {
                        String attribute = utf(in.readUnsignedShort());
                        int length = in.readInt();
                        if (!attribute.equals("ConstantValue")) {
                            in.skipNBytes(length);
                            continue;
                        }
                        int k = in.readUnsignedShort();
                        constant = tag[k] == 8 ? utf(a[k]) : value[k];
                    }
                    fields.add(new Field(flags, fieldName, desc, constant));
                }
                for (int i = in.readUnsignedShort(); i > 0; i--) {
                    int flags = in.readUnsignedShort();
                    String methodName = utf(in.readUnsignedShort()), desc = utf(in.readUnsignedShort());
                    int maxStack = 0, maxLocals = 0;
                    byte[] code = null;
                    List<Handler> handlers = new ArrayList<>();
                    List<int[]> lines = new ArrayList<>();
                    for (int j = in.readUnsignedShort(); j > 0; j--) {
                        String attribute = utf(in.readUnsignedShort());
                        int length = in.readInt();
                        if (!attribute.equals("Code")) {
                            in.skipNBytes(length);
                            continue;
                        }
                        maxStack = in.readUnsignedShort();
                        maxLocals = in.readUnsignedShort();
                        code = in.readNBytes(in.readInt());
                        for (int k = in.readUnsignedShort(); k > 0; k--) {
                            int start = in.readUnsignedShort(), end = in.readUnsignedShort();
                            int handler = in.readUnsignedShort(), type = in.readUnsignedShort();
                            handlers.add(new Handler(start, end, handler, type == 0 ? null : className(type)));
                        }
                        for (int k = in.readUnsignedShort(); k > 0; k--) {
                            String codeAttribute = utf(in.readUnsignedShort());
                            int codeLength = in.readInt();
                            if (!codeAttribute.equals("LineNumberTable")) {
                                in.skipNBytes(codeLength);
                                continue;
                            }
                            for (int l = in.readUnsignedShort(); l > 0; l--) {
                                lines.add(new int[] { in.readUnsignedShort(), in.readUnsignedShort() });
                            }
                        }
                    }
                    methods.add(new Method(flags, methodName, desc, maxStack, maxLocals, code, handlers, lines));
                }
                for (int i = in.readUnsignedShort(); i > 0; i--) {
                    String attribute = utf(in.readUnsignedShort());
                    int length = in.readInt();
                    if (attribute.equals("SourceFile")) {
                        sourceFile = utf(in.readUnsignedShort());
                        continue;
                    }
                    if (!attribute.equals("BootstrapMethods")) {
                        in.skipNBytes(length);
                        continue;
                    }
                    for (int j = in.readUnsignedShort(); j > 0; j--) {
                        int handle = in.readUnsignedShort();
                        int[] bootstrap = new int[1 + in.readUnsignedShort()];
                        bootstrap[0] = handle;
                        for (int k = 1; k < bootstrap.length; k++) bootstrap[k] = in.readUnsignedShort();
                        bootstraps.add(bootstrap);
                    }
                }
            }

            String utf(int i) {
                return (String) value[i];
            }

            String className(int i) {
                return utf(a[i]);
            }

            /** A field or method reference. */
            Ref ref(int i) {
                return new Ref(className(a[i]), utf(a[b[i]]), utf(b[b[i]]));
            }
        }

        static ClassDef translate(ClassFile cf) {
            ClassDef cd = new ClassDef(desc(cf.name), cf.access & 0x7611, cf.superName == null ? null : desc(cf.superName));
            if (cf.sourceFile != null) cd.sourceFile = str(cf.sourceFile).s();
            for (String i : cf.interfaces) cd.interfaces.add(type(desc(i)).desc());
            for (Field f : cf.fields) {
                Object value = (f.access() & 8) != 0 ? f.value() : null; // starting values of static fields
                if (value instanceof String s) str(s);
                cd.fields.add(new FieldDef(field(cd.desc, f.name(), f.desc()), f.access() & 0x50dd, value));
            }
            for (Method m : cf.methods) {
                if ((m.access() & 0x20) != 0) throw unsupported("synchronized method " + m.name());
                Ref self = new Ref(cf.name, m.name(), m.desc());
                int flags = m.access() & 0x1ddd | (staysPrivate(self) ? 2 : 0) | (m.name().startsWith("<") ? 0x10000 : 0); // 0x10000: constructor
                Code code = m.code() == null ? null : new Translator(cf, m).translate();
                cd.methods.add(new MethodDef(method(cd.desc, dexName(self), m.desc()), flags, code));
            }
            return cd;
        }

        // ---- Dalvik code ----

        /** One method's Dalvik instructions, in 16-bit units, with references and branches filled in later. */
        static final class Code {
            final int registers, ins;
            int outs, n;
            short[] w = new short[256];
            final List<Object[]> refs = new ArrayList<>();      // {position, reference}: a 16-bit index to fill in
            final List<int[]> branches = new ArrayList<>();     // {position, instruction address, target label, bits}
            final List<Object[]> payloads = new ArrayList<>();  // {position, instruction address, Switch}
            final List<int[]> tries = new ArrayList<>();        // {start address, length, handler list}
            final List<List<Object>> handlerLists = new ArrayList<>(); // type (null: any), address, type, address...
            final List<int[]> writeBack = new ArrayList<>();
            final List<int[]> lines = new ArrayList<>(); // {address, source line}, for stack traces
            int params;                                   // argument count, without `this`
            int nextTemp;

            Code(int registers, int ins) {
                if (registers > 256) throw unsupported("method needing more than 256 registers");
                this.registers = registers;
                this.ins = ins;
            }

            void u(int unit) {
                if (n == w.length) w = Arrays.copyOf(w, n * 2);
                w[n++] = (short) unit;
            }

            void u32(int v) {
                u(v);
                u(v >>> 16);
            }

            void index(Object ref) {
                refs.add(new Object[] { n, ref });
                u(0);
            }

            // Instruction formats, named as in the Dalvik docs: register widths A/B = 4 bits, AA/BB/CC = 8, AAAA = 16
            void f10x(int op) { u(op); }
            void f11x(int op, int aa) { u(op | aa << 8); }
            void f12x(int op, int a, int b) { u(op | a << 8 | b << 12); }
            void f21c(int op, int aa, Object ref) { f11x(op, aa); index(ref); }
            void f21s(int op, int aa, int literal) { f11x(op, aa); u(literal); }
            void f22b(int op, int aa, int bb, int literal) { f11x(op, aa); u(bb | (literal & 0xff) << 8); }
            void f22c(int op, int a, int b, Object ref) { f12x(op, a, b); index(ref); }
            void f22s(int op, int a, int b, int literal) { f12x(op, a, b); u(literal); }
            void f22x(int op, int aa, int bbbb) { f11x(op, aa); u(bbbb); }
            void f23x(int op, int aa, int bb, int cc) { f11x(op, aa); u(bb | cc << 8); }
            void f31i(int op, int aa, int literal) { f11x(op, aa); u32(literal); }
            void f3rc(int op, int count, Object ref, int first) { f11x(op, count); index(ref); u(first); }

            void f51l(int op, int aa, long literal) {
                f11x(op, aa);
                for (int i = 0; i < 4; i++) u((int) (literal >>> 16 * i));
            }

            void branch(int at, int label, int bits) {
                branches.add(new int[] { n, at, label, bits });
                u(0);
                if (bits == 32) u(0);
            }

            void f21t(int op, int aa, int label) { int at = n; f11x(op, aa); branch(at, label, 16); }
            void f22t(int op, int a, int b, int label) { int at = n; f12x(op, a, b); branch(at, label, 16); }
            void goto32(int label) { int at = n; u(0x2a); branch(at, label, 32); }

            void switch31t(int op, int aa, Translator.Switch s) {
                int at = n;
                f11x(op, aa);
                payloads.add(new Object[] { n, at, s });
                u32(0);
            }

            void move(char kind, int to, int from) {
                if (to != from) f22x(kind == 'J' ? 0x05 : kind == 'L' ? 0x08 : 0x02, to, from); // move*/from16
            }

            void constInt(int reg, int v) {
                if (v == (short) v) f21s(0x13, reg, v); else f31i(0x14, reg, v);
            }

            void constWide(int reg, long v) {
                if (v == (short) v) f21s(0x16, reg, (int) v); else f51l(0x18, reg, v);
            }

            /**
             * For instructions that can only name v0-v15: call this for each register operand, write the instruction
             * with the registers it returns, then call endLow(). Registers above v15 go through v0-v3 instead.
             */
            int low(int reg, char kind, boolean written) {
                if (reg < 16) return reg;
                int temp = nextTemp;
                nextTemp += kind == 'J' ? 2 : 1;
                if (nextTemp > TEMPS) throw unsupported("instruction needing more scratch registers");
                if (written) writeBack.add(new int[] { kind, reg, temp }); else move(kind, temp, reg);
                return temp;
            }

            void endLow() {
                for (int[] m : writeBack) move((char) m[0], m[1], m[2]);
                writeBack.clear();
                nextTemp = 0;
            }

            /** Adds switch tables after the code and fills in branch offsets; labels maps each target to its address. */
            void finish(Map<Integer, Integer> labels) {
                for (Object[] p : payloads) {
                    if (n % 2 != 0) u(0); // tables must start 4-byte aligned
                    int at = (int) p[1];
                    Translator.Switch s = (Translator.Switch) p[2];
                    w[(int) p[0]] = (short) (n - at);
                    w[(int) p[0] + 1] = (short) ((n - at) >>> 16);
                    u(s.packed() ? 0x100 : 0x200);
                    u(s.labels().length);
                    for (int key : s.keys()) u32(key);
                    for (int label : s.labels()) u32(labels.get(label) - at);
                }
                for (int[] b : branches) {
                    int offset = labels.get(b[2]) - b[1];
                    if (b[3] == 16 && offset != (short) offset) throw unsupported("branch over more than 32K units");
                    w[b[0]] = (short) offset;
                    if (b[3] == 32) w[b[0] + 1] = (short) (offset >>> 16);
                }
            }
        }

        /**
         * Translates one method. Java bytecode works on a stack; Dalvik on registers. Each local variable and each
         * stack slot gets a fixed register (the method's arguments must be the last ones), so an instruction that
         * pops two ints and pushes their sum becomes an add of two registers into the first.
         */
        static final class Translator {
            /** The stack before an instruction, a kind per slot ("I", "J" + "-", "L"), and whether `this` exists yet. */
            record State(List<String> stack, boolean init) {}
            record Switch(boolean packed, int[] keys, int[] labels, int def) {}

            final ClassFile cf;
            final Method m;
            final byte[] b;
            final int argWords, stackBase, registers;
            final TreeMap<Integer, State> states = new TreeMap<>();
            final List<Integer> work = new ArrayList<>();
            // A new object only gets a register once its constructor is called (Android doesn't allow copying it
            // before that), so until then its stack slot is "N<bytecode offset>", and `this` before super() is "T".
            final Map<String, String> newTypes = new HashMap<>();
            Code c;
            boolean emitting;
            int pc;

            Translator(ClassFile cf, Method m) {
                this.cf = cf;
                this.m = m;
                b = m.code();
                argWords = ((m.access() & 8) != 0 ? 0 : 1) + words(params(m.desc()));
                stackBase = TEMPS + m.maxLocals() - argWords;
                registers = stackBase + m.maxStack() + argWords;
            }

            int local(int i) { return i < argWords ? registers - argWords + i : TEMPS + i - argWords; }
            int stack(int slot) { return stackBase + slot; }
            int u1(int p) { return b[p] & 0xff; }
            int u2(int p) { return u1(p) << 8 | u1(p + 1); }
            int s4(int p) { return u2(p) << 16 | u2(p + 2); }

            Code translate() {
                try {
                    // Pass 1: find every reachable instruction and the stack before it. Its code is thrown away.
                    c = new Code(registers, argWords);
                    flow(0, new State(List.of(), !m.name().equals("<init>")));
                    while (!work.isEmpty()) {
                        pc = work.remove(work.size() - 1);
                        State out = step(states.get(pc));
                        for (Handler h : m.handlers()) {
                            if (pc >= h.start() && pc < h.end()) flow(h.handler(), new State(List.of("L"), true));
                        }
                        for (int target : successors()) flow(target, out);
                    }
                    // Pass 2: translate them in order.
                    c = new Code(registers, argWords);
                    emitting = true;
                    Map<Integer, Integer> address = new HashMap<>();
                    Set<Integer> handlerStarts = new HashSet<>();
                    for (Handler h : m.handlers()) handlerStarts.add(h.handler());
                    for (Map.Entry<Integer, State> e : states.entrySet()) {
                        pc = e.getKey();
                        address.put(pc, c.n);
                        if (handlerStarts.contains(pc)) c.f11x(0x0d, stack(0)); // move-exception
                        step(e.getValue());
                    }
                    tries(address, c.n);
                    for (int[] l : m.lines()) c.lines.add(new int[] { at(l[0], address, c.n), l[1] });
                    c.lines.sort(Comparator.comparingInt(l -> l[0]));
                    c.params = params(m.desc()).size();
                    c.finish(address);
                    return c;
                } catch (RuntimeException e) {
                    throw new IllegalStateException(cf.name + "." + m.name() + " at bytecode " + pc + ": " + e.getMessage(), e);
                }
            }

            void flow(int target, State s) {
                State old = states.get(target);
                if (old == null) {
                    states.put(target, s);
                    work.add(target);
                } else if (old.stack().size() != s.stack().size() || old.init() != s.init()) {
                    throw unsupported("different stacks where code paths join");
                }
            }

            static void push(List<String> st, char kind) {
                st.add(String.valueOf(kind));
                if (kind == 'J') st.add("-");
            }

            static void pop(List<String> st, int count) {
                for (int i = 0; i < count; i++) {
                    String k = st.remove(st.size() - 1);
                    if (k.equals("T") || k.startsWith("N")) throw unsupported("use of an object before its constructor");
                }
            }

            /** Translates the instruction at pc (when emitting), returning the stack after it. */
            State step(State in) {
                List<String> st = new ArrayList<>(in.stack());
                boolean init = in.init();
                int op = u1(pc), d = st.size();
                boolean wide = op == 196;
                if (wide) op = u1(pc + 1);
                int index = wide ? u2(pc + 2) : pc + 1 < b.length ? u1(pc + 1) : 0; // local variable, for loads, stores, iinc
                if (op >= 21 && op <= 45) { // iload ... aload_3
                    int i = op <= 25 ? index : (op - 26) % 4;
                    char k = "IJIJL".charAt(op <= 25 ? op - 21 : (op - 26) / 4);
                    if (k == 'L' && i == 0 && !init) st.add("T");
                    else {
                        c.move(k, stack(d), local(i));
                        push(st, k);
                    }
                } else if (op >= 54 && op <= 78) { // istore ... astore_3
                    int i = op <= 58 ? index : (op - 59) % 4;
                    char k = "IJIJL".charAt(op <= 58 ? op - 54 : (op - 59) / 4);
                    pop(st, k == 'J' ? 2 : 1);
                    c.move(k, local(i), stack(st.size()));
                } else if (op >= 96 && op <= 115) { // add, sub, mul, div, rem
                    int t = (op - 96) % 4, w = t % 2 == 1 ? 2 : 1;
                    c.f23x(new int[] { 0x90, 0x9b, 0xa6, 0xab }[t] + (op - 96) / 4, stack(d - 2 * w), stack(d - 2 * w), stack(d - w));
                    pop(st, 2 * w);
                    push(st, w == 2 ? 'J' : 'I');
                } else if (op >= 116 && op <= 119) { // neg
                    char k = op % 2 == 1 ? 'J' : 'I';
                    unary(new int[] { 0x7b, 0x7d, 0x7f, 0x80 }[op - 116], d - (k == 'J' ? 2 : 1), k, k);
                } else if (op >= 120 && op <= 131) { // shl, shr, ushr, and, or, xor
                    int i = op - 120, aw = i % 2 == 1 ? 2 : 1, bw = i < 6 ? 1 : aw;
                    int[] ops = { 0x98, 0xa3, 0x99, 0xa4, 0x9a, 0xa5, 0x95, 0xa0, 0x96, 0xa1, 0x97, 0xa2 };
                    c.f23x(ops[i], stack(d - aw - bw), stack(d - aw - bw), stack(d - bw));
                    pop(st, aw + bw);
                    push(st, aw == 2 ? 'J' : 'I');
                } else if (op >= 133 && op <= 147) { // conversions, in the same order in both formats
                    int i = op - 133;
                    char from = "IIIJJJIIIJJJIII".charAt(i), to = "JIJIIJIJJIJIIII".charAt(i);
                    unary(0x81 + i, d - (from == 'J' ? 2 : 1), to, from);
                    pop(st, from == 'J' ? 2 : 1);
                    push(st, to);
                } else if (op >= 148 && op <= 152) { // lcmp, fcmpl, fcmpg, dcmpl, dcmpg
                    int w = op == 148 || op >= 151 ? 2 : 1;
                    c.f23x(new int[] { 0x31, 0x2d, 0x2e, 0x2f, 0x30 }[op - 148], stack(d - 2 * w), stack(d - 2 * w), stack(d - w));
                    pop(st, 2 * w);
                    push(st, 'I');
                } else if (op >= 153 && op <= 158 || op == 198 || op == 199) { // if<cond> against zero, ifnull
                    c.f21t(0x38 + (op >= 198 ? op - 198 : op - 153), stack(d - 1), pc + (short) u2(pc + 1));
                    pop(st, 1);
                } else if (op >= 159 && op <= 166) { // if_icmp<cond>, if_acmp<cond>
                    char k = op <= 164 ? 'I' : 'L';
                    int x = c.low(stack(d - 2), k, false), y = c.low(stack(d - 1), k, false);
                    c.f22t(0x32 + (op <= 164 ? op - 159 : op - 165), x, y, pc + (short) u2(pc + 1));
                    c.endLow();
                    pop(st, 2);
                } else if (op >= 172 && op <= 177) { // return
                    if (op == 177) c.f10x(0x0e);
                    else {
                        int w = op == 173 || op == 175 ? 2 : 1;
                        c.f11x(op == 176 ? 0x11 : w == 2 ? 0x10 : 0x0f, stack(d - w));
                    }
                    st.clear();
                } else switch (op) {
                    case 0 -> {}
                    case 1 -> { c.constInt(stack(d), 0); push(st, 'L'); }
                    case 2, 3, 4, 5, 6, 7, 8 -> { c.constInt(stack(d), op - 3); push(st, 'I'); }
                    case 9, 10 -> { c.constWide(stack(d), op - 9); push(st, 'J'); }
                    case 11, 12, 13 -> { c.constInt(stack(d), Float.floatToRawIntBits(op - 11)); push(st, 'I'); }
                    case 14, 15 -> { c.constWide(stack(d), Double.doubleToRawLongBits(op - 14)); push(st, 'J'); }
                    case 16 -> { c.constInt(stack(d), (byte) u1(pc + 1)); push(st, 'I'); }
                    case 17 -> { c.constInt(stack(d), (short) u2(pc + 1)); push(st, 'I'); }
                    case 18, 19, 20 -> ldc(st, op == 18 ? u1(pc + 1) : u2(pc + 1));
                    case 46, 47, 48, 49, 50, 52, 53 -> { // array loads (51, baload, is for both byte and boolean arrays)
                        c.f23x(new int[] { 0x44, 0x45, 0x44, 0x45, 0x46, 0, 0x49, 0x4a }[op - 46], stack(d - 2), stack(d - 2), stack(d - 1));
                        pop(st, 2);
                        push(st, "IJIJL?II".charAt(op - 46));
                    }
                    case 79, 80, 81, 82, 83, 85, 86 -> { // array stores
                        int w = op == 80 || op == 82 ? 2 : 1, array = d - w - 2;
                        c.f23x(new int[] { 0x4b, 0x4c, 0x4b, 0x4c, 0x4d, 0, 0x50, 0x51 }[op - 79], stack(array + 2), stack(array), stack(array + 1));
                        pop(st, w + 2);
                    }
                    case 87 -> pop(st, 1);
                    case 88 -> pop(st, 2);
                    case 89 -> { // dup
                        String top = st.get(d - 1);
                        copy(top, d - 1, d);
                        st.add(top);
                    }
                    case 90 -> { // dup_x1: a b -> b a b
                        String x = st.get(d - 2), y = st.get(d - 1);
                        copy(y, d - 1, d);
                        copy(x, d - 2, d - 1);
                        copy(y, d, d - 2);
                        st.set(d - 2, y);
                        st.set(d - 1, x);
                        st.add(y);
                    }
                    case 132 -> { // iinc
                        int r = local(index), v = wide ? (short) u2(pc + 4) : (byte) u1(pc + 2);
                        if (v == (byte) v) c.f22b(0xd8, r, r, v); // add-int/lit8
                        else {
                            int x = c.low(r, 'I', true), y = c.low(r, 'I', false);
                            c.f22s(0xd0, x, y, v); // add-int/lit16
                            c.endLow();
                        }
                    }
                    case 167 -> c.goto32(pc + (short) u2(pc + 1));
                    case 200 -> c.goto32(pc + s4(pc + 1));
                    case 170, 171 -> { // tableswitch, lookupswitch: Dalvik falls through when nothing matches
                        Switch s = decodeSwitch();
                        c.switch31t(s.packed() ? 0x2b : 0x2c, stack(d - 1), s);
                        c.goto32(s.def());
                        pop(st, 1);
                    }
                    case 178, 179, 180, 181 -> { // getstatic, putstatic, getfield, putfield
                        Ref r = cf.ref(u2(pc + 1));
                        FieldRef f = field(desc(r.owner()), r.name(), r.desc());
                        int v = variant(r.desc()), w = words(r.desc());
                        char k = kind(r.desc());
                        if (op == 178) {
                            c.f21c(0x60 + v, stack(d), f);
                            push(st, k);
                        } else if (op == 179) {
                            c.f21c(0x67 + v, stack(d - w), f);
                            pop(st, w);
                        } else if (op == 180) {
                            pop(st, 1);
                            int x = c.low(stack(d - 1), k, true), o = c.low(stack(d - 1), 'L', false);
                            c.f22c(0x52 + v, x, o, f);
                            c.endLow();
                            push(st, k);
                        } else {
                            pop(st, w + 1);
                            int x = c.low(stack(d - w), k, false), o = c.low(stack(d - w - 1), 'L', false);
                            c.f22c(0x59 + v, x, o, f);
                            c.endLow();
                        }
                    }
                    case 182, 183, 184, 185 -> init = invoke(st, op, init);
                    case 186 -> { // invokedynamic: a lambda, made by a generated class's factory method
                        int i = u2(pc + 1);
                        String indyDesc = cf.utf(cf.b[cf.b[i]]);
                        int w = words(params(indyDesc));
                        if (emitting) {
                            MethodRef create = lambda(cf, cf.bootstraps.get(cf.a[i]), cf.utf(cf.a[cf.b[i]]), indyDesc);
                            c.f3rc(0x77, w, create, w == 0 ? 0 : stack(d - w));
                            c.f11x(0x0c, stack(d - w));
                            c.outs = Math.max(c.outs, w);
                        }
                        pop(st, w);
                        push(st, 'L');
                    }
                    case 187 -> { // new: no code until the constructor call
                        newTypes.put("N" + pc, desc(cf.className(u2(pc + 1))));
                        st.add("N" + pc);
                    }
                    case 188, 189 -> { // newarray, anewarray
                        String t = op == 188 ? "[" + "ZCFDBSIJ".charAt(u1(pc + 1) - 4) : "[" + desc(cf.className(u2(pc + 1)));
                        pop(st, 1);
                        int x = c.low(stack(d - 1), 'L', true), y = c.low(stack(d - 1), 'I', false);
                        c.f22c(0x23, x, y, type(t));
                        c.endLow();
                        push(st, 'L');
                    }
                    case 190 -> { // arraylength
                        pop(st, 1);
                        unary(0x21, d - 1, 'I', 'L');
                        push(st, 'I');
                    }
                    case 191 -> { // athrow
                        c.f11x(0x27, stack(d - 1));
                        st.clear();
                    }
                    case 192 -> c.f21c(0x1f, stack(d - 1), type(desc(cf.className(u2(pc + 1))))); // checkcast
                    case 193 -> { // instanceof
                        pop(st, 1);
                        int x = c.low(stack(d - 1), 'I', true), y = c.low(stack(d - 1), 'L', false);
                        c.f22c(0x20, x, y, type(desc(cf.className(u2(pc + 1)))));
                        c.endLow();
                        push(st, 'I');
                    }
                    default -> throw unsupported("bytecode " + op);
                }
                return new State(st, init);
            }

            /** Copies a stack slot (for dup), except for objects whose constructor hasn't run: those have no register. */
            void copy(String kind, int from, int to) {
                if (kind.equals("-")) throw unsupported("dup of half a long or double");
                if (kind.equals("I") || kind.equals("L")) c.move(kind.charAt(0), stack(to), stack(from));
            }

            /** An instruction with 4-bit registers that reads a stack slot and writes its result to the same slot. */
            void unary(int op, int slot, char to, char from) {
                int x = c.low(stack(slot), to, true), y = c.low(stack(slot), from, false);
                c.f12x(op, x, y);
                c.endLow();
            }

            void ldc(List<String> st, int i) {
                int d = st.size();
                switch (cf.tag[i]) {
                    case 3 -> { c.constInt(stack(d), (Integer) cf.value[i]); push(st, 'I'); }
                    case 4 -> { c.constInt(stack(d), Float.floatToRawIntBits((Float) cf.value[i])); push(st, 'I'); }
                    case 5 -> { c.constWide(stack(d), (Long) cf.value[i]); push(st, 'J'); }
                    case 6 -> { c.constWide(stack(d), Double.doubleToRawLongBits((Double) cf.value[i])); push(st, 'J'); }
                    case 7 -> { c.f21c(0x1c, stack(d), type(desc(cf.className(i)))); push(st, 'L'); }
                    case 8 -> { c.f21c(0x1a, stack(d), str(cf.utf(cf.a[i]))); push(st, 'L'); }
                    default -> throw unsupported("constant of kind " + cf.tag[i]);
                }
            }

            /** Method calls. The arguments are already in consecutive registers, as Dalvik's "range" calls want. */
            boolean invoke(List<String> st, int op, boolean init) {
                Ref r = cf.ref(u2(pc + 1));
                MethodRef ref = method(desc(r.owner()), dexName(r), r.desc());
                int w = (op == 184 ? 0 : 1) + words(params(r.desc())), d = st.size(), base = d - w;
                c.outs = Math.max(c.outs, w);
                if (r.name().equals("<init>")) {
                    String object = st.get(base);
                    pop(st, w - 1);
                    st.remove(base);
                    if (object.startsWith("N")) { // new object: create it now, then fill in the copies dup made
                        c.f21c(0x22, stack(base), type(newTypes.get(object)));
                        c.f3rc(0x76, w, ref, stack(base));
                        for (int j = 0; j < st.size(); j++) {
                            if (st.get(j).equals(object)) {
                                c.move('L', stack(j), stack(base));
                                st.set(j, "L");
                            }
                        }
                    } else if (object.equals("T") && w == 1) { // super() in a constructor, on `this` itself
                        c.f3rc(0x76, 1, ref, local(0));
                        init = true;
                    } else throw unsupported("constructor call on an existing object or with arguments to super()");
                    return init;
                }
                int dop = switch (op) {
                    case 182, 183 -> staysPrivate(r) ? 0x76               // invoke-direct/range: a private method
                            : op == 182 || r.owner().equals(cf.name) ? 0x74 // invoke-virtual/range (incl. shared private)
                            : 0x75;                                       // invoke-super/range
                    case 184 -> 0x77;                                    // invoke-static/range
                    default -> 0x78;                                     // invoke-interface/range
                };
                c.f3rc(dop, w, ref, w == 0 ? 0 : stack(base));
                pop(st, w);
                String rt = ret(r.desc());
                if (!rt.equals("V")) {
                    char k = kind(rt);
                    c.f11x(k == 'J' ? 0x0b : k == 'L' ? 0x0c : 0x0a, stack(base)); // move-result*
                    push(st, k);
                }
                return init;
            }

            List<Integer> successors() {
                int op = u1(pc);
                if (op == 167) return List.of(pc + (short) u2(pc + 1));
                if (op == 200) return List.of(pc + s4(pc + 1));
                if (op >= 172 && op <= 177 || op == 191) return List.of();
                List<Integer> list = new ArrayList<>();
                if (op == 170 || op == 171) {
                    Switch s = decodeSwitch();
                    for (int label : s.labels()) list.add(label);
                    list.add(s.def());
                    return list;
                }
                list.add(pc + length());
                if (op >= 153 && op <= 166 || op == 198 || op == 199) list.add(pc + (short) u2(pc + 1));
                return list;
            }

            Switch decodeSwitch() {
                int p = (pc + 4) & ~3; // the operands start 4-byte aligned
                boolean packed = u1(pc) == 170;
                int count = packed ? s4(p + 8) - s4(p + 4) + 1 : s4(p + 4);
                int[] keys = new int[packed ? 1 : count], labels = new int[count];
                if (packed) keys[0] = s4(p + 4);
                for (int i = 0; i < count; i++) {
                    if (!packed) keys[i] = s4(p + 8 + 8 * i);
                    labels[i] = pc + s4(packed ? p + 12 + 4 * i : p + 12 + 8 * i);
                }
                return new Switch(packed, keys, labels, pc + s4(p));
            }

            int length() {
                int op = u1(pc);
                if (op == 170 || op == 171) {
                    int p = (pc + 4) & ~3;
                    return (op == 170 ? p + 12 + 4 * (s4(p + 8) - s4(p + 4) + 1) : p + 8 + 8 * s4(p + 4)) - pc;
                }
                if (op == 196) return u1(pc + 1) == 132 ? 6 : 4;
                if (op == 185 || op == 186 || op == 200 || op == 201) return 5;
                if (op == 197) return 4;
                if (op == 17 || op == 19 || op == 20 || op == 132 || op >= 153 && op <= 168 || op >= 178 && op <= 184
                        || op == 187 || op == 189 || op == 192 || op == 193 || op == 198 || op == 199) return 3;
                if (op == 16 || op == 18 || op >= 21 && op <= 25 || op >= 54 && op <= 58 || op == 169 || op == 188) return 2;
                return 1;
            }

            /**
             * try/catch: Java lists (range, handler) pairs, which may overlap; Dalvik wants separate ranges, each with
             * its full list of handlers in order. So split at every range edge and list the handlers covering each piece.
             */
            void tries(Map<Integer, Integer> address, int end) {
                TreeSet<Integer> edges = new TreeSet<>();
                for (Handler h : m.handlers()) {
                    if (!address.containsKey(h.handler())) continue;
                    edges.add(at(h.start(), address, end));
                    edges.add(at(h.end(), address, end));
                }
                int[] last = null;
                for (int from : edges) {
                    Integer to = edges.higher(from);
                    if (to == null) break;
                    List<Object> list = new ArrayList<>();
                    Set<Object> seen = new HashSet<>();
                    for (Handler h : m.handlers()) {
                        if (!address.containsKey(h.handler()) || at(h.start(), address, end) > from || at(h.end(), address, end) < to) continue;
                        Object t = h.type() == null ? null : type(desc(h.type()));
                        if (!seen.add(t)) continue; // the first handler for a type wins
                        list.add(t);
                        list.add(address.get(h.handler()));
                        if (t == null) break; // a catch-all: nothing after it can run
                    }
                    if (list.isEmpty()) continue;
                    int i = c.handlerLists.indexOf(list);
                    if (i < 0) {
                        i = c.handlerLists.size();
                        c.handlerLists.add(list);
                    }
                    if (last != null && last[0] + last[1] == from && last[2] == i) last[1] += to - from;
                    else c.tries.add(last = new int[] { from, to - from, i });
                }
            }

            /** The address of the first translated instruction at or after a bytecode offset. */
            int at(int offset, Map<Integer, Integer> address, int end) {
                Integer k = states.ceilingKey(offset);
                return k == null ? end : address.get(k);
            }
        }

        /**
         * A lambda becomes a class implementing its interface: fields for the values it captured, a constructor and
         * a static factory (called where the lambda is created), and the interface method, which calls the lambda's
         * body (a method javac put in the enclosing class) with the captured values and its own arguments.
         */
        static MethodRef lambda(ClassFile cf, int[] bootstrap, String samName, String indyDesc) {
            Ref factory = cf.ref(cf.b[bootstrap[0]]);
            if (!factory.owner().equals("java/lang/invoke/LambdaMetafactory") || !factory.name().equals("metafactory")) {
                throw unsupported("invokedynamic other than a plain lambda");
            }
            String samDesc = cf.utf(cf.a[bootstrap[1]]), instDesc = cf.utf(cf.a[bootstrap[3]]);
            int implKind = cf.a[bootstrap[2]];
            Ref impl = cf.ref(cf.b[bootstrap[2]]);
            String self = desc(cf.name + "$$Lambda$" + lambdaCounts.merge(cf.name, 1, Integer::sum));
            List<String> caps = params(indyDesc);
            String iface = ret(indyDesc), capDesc = "(" + String.join("", caps) + ")";
            int capWords = words(caps);

            ClassDef cd = new ClassDef(self, 0x1010, "Ljava/lang/Object;"); // final synthetic
            if (cf.sourceFile != null) cd.sourceFile = str(cf.sourceFile).s();
            cd.interfaces.add(type(iface).desc());
            List<FieldRef> fields = new ArrayList<>();
            for (int i = 0; i < caps.size(); i++) {
                fields.add(field(self, "f" + i, caps.get(i)));
                cd.fields.add(new FieldDef(fields.get(i), 0x1010, null));
            }

            MethodRef init = method(self, "<init>", capDesc + "V");
            Code c = new Code(TEMPS + 1 + capWords, 1 + capWords); // this, captured values
            c.f3rc(0x76, 1, method("Ljava/lang/Object;", "<init>", "()V"), TEMPS);
            for (int i = 0, r = TEMPS + 1; i < caps.size(); r += words(caps.get(i++))) {
                int x = c.low(r, kind(caps.get(i)), false), o = c.low(TEMPS, 'L', false);
                c.f22c(0x59 + variant(caps.get(i)), x, o, fields.get(i));
                c.endLow();
            }
            c.f10x(0x0e);
            c.outs = 1;
            cd.methods.add(new MethodDef(init, 0x10001, c));

            MethodRef create = method(self, "create", capDesc + iface);
            c = new Code(TEMPS + 1 + capWords, capWords); // the new object, then the captured values right after it
            c.f21c(0x22, TEMPS, type(self));
            c.f3rc(0x76, 1 + capWords, init, TEMPS);
            c.f11x(0x11, TEMPS);
            c.outs = 1 + capWords;
            cd.methods.add(new MethodDef(create, 0x1009, c));

            List<String> sam = params(samDesc), inst = params(instDesc);
            List<String> implArgs = new ArrayList<>(params(impl.desc()));
            if (implKind != 6) implArgs.add(0, desc(impl.owner())); // not static: the object it's called on comes first
            int block = words(implArgs), samWords = 1 + words(sam);
            if (block != capWords + samWords - 1) throw unsupported("lambda whose arguments don't line up");
            c = new Code(TEMPS + block + samWords, samWords); // the body's arguments, then this and own arguments
            int self0 = TEMPS + block, slot = TEMPS;
            for (int i = 0; i < caps.size(); slot += words(caps.get(i++))) {
                int x = c.low(slot, kind(caps.get(i)), true), o = c.low(self0, 'L', false);
                c.f22c(0x52 + variant(caps.get(i)), x, o, fields.get(i));
                c.endLow();
            }
            for (int i = 0, r = self0 + 1; i < sam.size(); i++) {
                String t = sam.get(i);
                c.move(kind(t), slot, r);
                if (!t.equals(inst.get(i))) { // e.g. Object in the interface, LocalDate in the lambda
                    if (kind(t) != 'L') throw unsupported("lambda needing boxing");
                    c.f21c(0x1f, slot, type(inst.get(i)));
                }
                slot += words(t);
                r += words(t);
            }
            int op = switch (implKind) {
                case 5, 7 -> 0x74; // virtual, or a private method (now virtual)
                case 6 -> 0x77;    // static
                case 9 -> 0x78;    // interface
                default -> throw unsupported("method reference of kind " + implKind);
            };
            c.f3rc(op, block, method(desc(impl.owner()), dexName(impl), impl.desc()), TEMPS);
            c.outs = block;
            String samRet = ret(samDesc), implRet = ret(impl.desc());
            if (samRet.equals("V")) c.f10x(0x0e);
            else {
                char k = kind(samRet);
                if (implRet.equals("V") || kind(implRet) != k) throw unsupported("lambda needing boxing");
                c.f11x(k == 'J' ? 0x0b : k == 'L' ? 0x0c : 0x0a, 0); // move-result* v0
                c.f11x(k == 'J' ? 0x10 : k == 'L' ? 0x11 : 0x0f, 0); // return* v0
            }
            cd.methods.add(new MethodDef(method(self, samName, samDesc), 0x11, c)); // public final
            classes.add(cd);
            return create;
        }

        // ---- Writing the dex file ----

        static final class Out {
            byte[] b = new byte[4096];
            int n;

            void u1(int v) {
                if (n == b.length) b = Arrays.copyOf(b, n * 2);
                b[n++] = (byte) v;
            }

            void u2(int v) { u1(v); u1(v >> 8); }
            void u4(int v) { u2(v); u2(v >>> 16); }
            void u8(long v) { u4((int) v); u4((int) (v >>> 32)); }
            void bytes(byte[] x, int length) { for (int i = 0; i < length; i++) u1(x[i]); }
            void align() { while (n % 4 != 0) u1(0); }
            void put2(int at, int v) { b[at] = (byte) v; b[at + 1] = (byte) (v >> 8); }
            void put4(int at, int v) { put2(at, v); put2(at + 2, v >>> 16); }

            void uleb(int v) {
                while ((v & ~0x7f) != 0) {
                    u1(v & 0x7f | 0x80);
                    v >>>= 7;
                }
                u1(v);
            }

            void sleb(int v) {
                while (true) {
                    int bits = v & 0x7f;
                    v >>= 7;
                    boolean done = v == 0 && (bits & 0x40) == 0 || v == -1 && (bits & 0x40) != 0;
                    u1(done ? bits : bits | 0x80);
                    if (done) return;
                }
            }
        }

        static <T> Map<T, Integer> indexes(List<T> list) {
            Map<T, Integer> map = new HashMap<>();
            for (int i = 0; i < list.size(); i++) map.put(list.get(i), i);
            return map;
        }

        /** Puts a class after its superclass and interfaces, as Android requires when they're in the same file. */
        static void place(ClassDef cd, Map<String, ClassDef> byDesc, Set<String> placed, List<ClassDef> order) {
            if (cd == null || !placed.add(cd.desc)) return;
            place(byDesc.get(cd.superDesc), byDesc, placed, order);
            for (String i : cd.interfaces) place(byDesc.get(i), byDesc, placed, order);
            order.add(cd);
        }

        static byte[] write() throws Exception {
            // Every list is sorted the way the format requires, which fixes the index numbers.
            List<String> stringList = new ArrayList<>(strings), typeList = new ArrayList<>(types);
            Map<String, Integer> si = indexes(stringList), ti = indexes(typeList);
            Comparator<List<String>> byTypes = (x, y) -> {
                for (int i = 0; i < Math.min(x.size(), y.size()); i++) {
                    int diff = Integer.compare(ti.get(x.get(i)), ti.get(y.get(i)));
                    if (diff != 0) return diff;
                }
                return Integer.compare(x.size(), y.size());
            };
            List<Proto> protoList = new ArrayList<>(protos);
            protoList.sort(Comparator.comparing((Proto p) -> ti.get(p.ret())).thenComparing(Proto::params, byTypes));
            Map<Proto, Integer> pi = indexes(protoList);
            List<FieldRef> fieldList = new ArrayList<>(fieldRefs);
            fieldList.sort(Comparator.comparing((FieldRef f) -> ti.get(f.cls())).thenComparing(f -> si.get(f.name()))
                    .thenComparing(f -> ti.get(f.type())));
            Map<FieldRef, Integer> fi = indexes(fieldList);
            List<MethodRef> methodList = new ArrayList<>(methodRefs);
            methodList.sort(Comparator.comparing((MethodRef m) -> ti.get(m.cls())).thenComparing(m -> si.get(m.name()))
                    .thenComparing(m -> pi.get(new Proto(ret(m.desc()), params(m.desc())))));
            Map<MethodRef, Integer> mi = indexes(methodList);
            Map<Object, Integer> index = new HashMap<>(); // what instructions refer to -> its index
            stringList.forEach(s -> index.put(new Str(s), si.get(s)));
            typeList.forEach(t -> index.put(new Type(t), ti.get(t)));
            index.putAll(fi);
            index.putAll(mi);

            Map<String, ClassDef> byDesc = new HashMap<>();
            for (ClassDef cd : classes) byDesc.put(cd.desc, cd);
            List<ClassDef> order = new ArrayList<>();
            Set<String> placed = new HashSet<>();
            for (ClassDef cd : classes) place(cd, byDesc, placed, order);

            int stringIds = 0x70, typeIds = stringIds + 4 * stringList.size(), protoIds = typeIds + 4 * typeList.size();
            int fieldIds = protoIds + 12 * protoList.size(), methodIds = fieldIds + 8 * fieldList.size();
            int classDefs = methodIds + 8 * methodList.size(), dataStart = classDefs + 32 * order.size();
            Out o = new Out();
            while (o.n < dataStart) o.u1(0); // header and id lists, filled in at the end
            List<int[]> map = new ArrayList<>(); // {section type, item count, offset}
            map.add(new int[] { 0x0000, 1, 0 });
            map.add(new int[] { 0x0001, stringList.size(), stringIds });
            map.add(new int[] { 0x0002, typeList.size(), typeIds });
            map.add(new int[] { 0x0003, protoList.size(), protoIds });
            map.add(new int[] { 0x0004, fieldList.size(), fieldIds });
            map.add(new int[] { 0x0005, methodList.size(), methodIds });
            map.add(new int[] { 0x0006, order.size(), classDefs });

            // Line numbers, so crash reports name the source line
            Map<Code, Integer> debugOffsets = new IdentityHashMap<>();
            int debugStart = o.n;
            for (ClassDef cd : order) {
                for (MethodDef md : cd.methods) {
                    if (md.code() == null || md.code().lines.isEmpty()) continue;
                    debugOffsets.put(md.code(), o.n);
                    writeLines(o, md.code());
                }
            }
            map.add(new int[] { 0x2003, debugOffsets.size(), debugStart });

            Map<Code, Integer> codeOffsets = new IdentityHashMap<>();
            int start = 0;
            for (ClassDef cd : order) {
                for (MethodDef md : cd.methods) {
                    if (md.code() == null) continue;
                    o.align();
                    if (codeOffsets.isEmpty()) start = o.n;
                    codeOffsets.put(md.code(), o.n);
                    writeCode(o, md.code(), index, debugOffsets.getOrDefault(md.code(), 0));
                }
            }
            map.add(new int[] { 0x2001, codeOffsets.size(), start });

            Map<List<String>, Integer> typeLists = new LinkedHashMap<>();
            for (Proto p : protoList) if (!p.params().isEmpty()) typeLists.put(p.params(), 0);
            for (ClassDef cd : order) if (!cd.interfaces.isEmpty()) typeLists.put(cd.interfaces, 0);
            o.align();
            map.add(new int[] { 0x1001, typeLists.size(), o.n });
            for (Map.Entry<List<String>, Integer> e : typeLists.entrySet()) {
                o.align();
                e.setValue(o.n);
                o.u4(e.getKey().size());
                for (String t : e.getKey()) o.u2(ti.get(t));
            }

            int[] stringData = new int[stringList.size()];
            map.add(new int[] { 0x2002, stringList.size(), o.n });
            for (int i = 0; i < stringList.size(); i++) {
                stringData[i] = o.n;
                o.uleb(stringList.get(i).length());
                ByteArrayOutputStream utf = new ByteArrayOutputStream();
                new DataOutputStream(utf).writeUTF(stringList.get(i)); // Java's "modified UTF-8", as dex uses
                o.bytes(Arrays.copyOfRange(utf.toByteArray(), 2, utf.size()), utf.size() - 2);
                o.u1(0);
            }

            Map<ClassDef, Integer> classData = new HashMap<>();
            Map<ClassDef, List<FieldDef>> statics = new HashMap<>();
            int classDataStart = o.n;
            for (ClassDef cd : order) {
                List<FieldDef> stat = new ArrayList<>(), inst = new ArrayList<>();
                for (FieldDef f : cd.fields) ((f.access() & 8) != 0 ? stat : inst).add(f);
                stat.sort(Comparator.comparing(f -> fi.get(f.ref())));
                inst.sort(Comparator.comparing(f -> fi.get(f.ref())));
                statics.put(cd, stat);
                List<MethodDef> direct = new ArrayList<>(), virtual = new ArrayList<>();
                for (MethodDef md : cd.methods) ((md.access() & 0x1000a) != 0 ? direct : virtual).add(md); // static, private or constructor
                direct.sort(Comparator.comparing(md -> mi.get(md.ref())));
                virtual.sort(Comparator.comparing(md -> mi.get(md.ref())));
                if (cd.fields.isEmpty() && cd.methods.isEmpty()) continue;
                classData.put(cd, o.n);
                o.uleb(stat.size());
                o.uleb(inst.size());
                o.uleb(direct.size());
                o.uleb(virtual.size());
                for (List<FieldDef> list : List.of(stat, inst)) {
                    int previous = 0;
                    for (FieldDef f : list) {
                        o.uleb(fi.get(f.ref()) - previous);
                        previous = fi.get(f.ref());
                        o.uleb(f.access());
                    }
                }
                for (List<MethodDef> list : List.of(direct, virtual)) {
                    int previous = 0;
                    for (MethodDef md : list) {
                        o.uleb(mi.get(md.ref()) - previous);
                        previous = mi.get(md.ref());
                        o.uleb(md.access());
                        o.uleb(md.code() == null ? 0 : codeOffsets.get(md.code()));
                    }
                }
            }
            map.add(new int[] { 0x2000, classData.size(), classDataStart });

            Map<ClassDef, Integer> staticValues = new HashMap<>();
            int staticStart = o.n;
            for (ClassDef cd : order) {
                List<FieldDef> stat = statics.get(cd);
                int count = 0;
                for (int i = 0; i < stat.size(); i++) if (stat.get(i).value() != null) count = i + 1;
                if (count == 0) continue;
                staticValues.put(cd, o.n);
                o.uleb(count);
                for (int i = 0; i < count; i++) writeValue(o, stat.get(i).ref().type(), stat.get(i).value(), si);
            }
            map.add(new int[] { 0x2005, staticValues.size(), staticStart });

            o.align();
            map.add(new int[] { 0x1000, 1, o.n });
            map.removeIf(e -> e[1] == 0);
            int mapOffset = o.n;
            o.u4(map.size());
            for (int[] e : map) {
                o.u2(e[0]);
                o.u2(0);
                o.u4(e[1]);
                o.u4(e[2]);
            }

            for (int i = 0; i < stringList.size(); i++) o.put4(stringIds + 4 * i, stringData[i]);
            for (int i = 0; i < typeList.size(); i++) o.put4(typeIds + 4 * i, si.get(typeList.get(i)));
            for (int i = 0; i < protoList.size(); i++) {
                Proto p = protoList.get(i);
                o.put4(protoIds + 12 * i, si.get(shorty(p)));
                o.put4(protoIds + 12 * i + 4, ti.get(p.ret()));
                o.put4(protoIds + 12 * i + 8, p.params().isEmpty() ? 0 : typeLists.get(p.params()));
            }
            for (int i = 0; i < fieldList.size(); i++) {
                FieldRef f = fieldList.get(i);
                o.put2(fieldIds + 8 * i, ti.get(f.cls()));
                o.put2(fieldIds + 8 * i + 2, ti.get(f.type()));
                o.put4(fieldIds + 8 * i + 4, si.get(f.name()));
            }
            for (int i = 0; i < methodList.size(); i++) {
                MethodRef m = methodList.get(i);
                o.put2(methodIds + 8 * i, ti.get(m.cls()));
                o.put2(methodIds + 8 * i + 2, pi.get(new Proto(ret(m.desc()), params(m.desc()))));
                o.put4(methodIds + 8 * i + 4, si.get(m.name()));
            }
            for (int i = 0; i < order.size(); i++) {
                ClassDef cd = order.get(i);
                int at = classDefs + 32 * i;
                o.put4(at, ti.get(cd.desc));
                o.put4(at + 4, cd.access);
                o.put4(at + 8, cd.superDesc == null ? -1 : ti.get(cd.superDesc));
                o.put4(at + 12, cd.interfaces.isEmpty() ? 0 : typeLists.get(cd.interfaces));
                o.put4(at + 16, cd.sourceFile == null ? -1 : si.get(cd.sourceFile));
                o.put4(at + 24, classData.getOrDefault(cd, 0));
                o.put4(at + 28, staticValues.getOrDefault(cd, 0));
            }

            // Header. Format version 039 (Android 9+) allows calling static methods of interfaces, like List.of().
            System.arraycopy("dex\n039\0".getBytes(StandardCharsets.US_ASCII), 0, o.b, 0, 8);
            int[] header = { o.n, 0x70, 0x12345678, 0, 0, mapOffset,
                    stringList.size(), stringIds, typeList.size(), typeIds, protoList.size(), protoIds,
                    fieldList.size(), fieldList.isEmpty() ? 0 : fieldIds, methodList.size(), methodIds,
                    order.size(), classDefs, o.n - dataStart, dataStart };
            for (int i = 0; i < header.length; i++) o.put4(32 + 4 * i, header[i]);
            byte[] dex = Arrays.copyOf(o.b, o.n);
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(dex, 32, dex.length - 32);
            System.arraycopy(sha1.digest(), 0, dex, 12, 20);
            Adler32 adler = new Adler32();
            adler.update(dex, 12, dex.length - 12);
            int checksum = (int) adler.getValue();
            for (int i = 0; i < 4; i++) dex[8 + i] = (byte) (checksum >>> 8 * i);
            return dex;
        }

        /**
         * Debug info, only line numbers: a start line, unnamed parameters, then for each line-table entry an
         * "advance address" and an "advance line" step, plus an opcode that records the position.
         */
        static void writeLines(Out o, Code c) {
            int address = 0, line = c.lines.get(0)[1];
            o.uleb(line);
            o.uleb(c.params);
            for (int i = 0; i < c.params; i++) o.uleb(0); // no parameter names
            for (int[] l : c.lines) {
                if (l[0] != address) { o.u1(0x01); o.uleb(l[0] - address); address = l[0]; }
                if (l[1] != line) { o.u1(0x02); o.sleb(l[1] - line); line = l[1]; }
                o.u1(0x0e); // record (address, line); in Dalvik's encoding, 0x0e advances neither
            }
            o.u1(0x00); // end
        }

        static void writeCode(Out o, Code c, Map<Object, Integer> index, int debugOffset) {
            for (Object[] r : c.refs) {
                int i = index.get(r[1]);
                if (i > 0xffff) throw unsupported("more than 65536 strings, types, fields or methods");
                c.w[(int) r[0]] = (short) i;
            }
            o.u2(c.registers);
            o.u2(c.ins);
            o.u2(c.outs);
            o.u2(c.tries.size());
            o.u4(debugOffset);
            o.u4(c.n);
            for (int i = 0; i < c.n; i++) o.u2(c.w[i]);
            if (c.tries.isEmpty()) return;
            if (c.n % 2 != 0) o.u2(0);
            Out h = new Out();
            int[] offsets = new int[c.handlerLists.size()];
            h.uleb(offsets.length);
            for (int i = 0; i < offsets.length; i++) {
                offsets[i] = h.n;
                List<Object> list = c.handlerLists.get(i);
                boolean catchAll = list.get(list.size() - 2) == null;
                int typed = list.size() / 2 - (catchAll ? 1 : 0);
                h.sleb(catchAll ? -typed : typed);
                for (int j = 0; j < list.size(); j += 2) {
                    if (list.get(j) != null) h.uleb(index.get(list.get(j)));
                    h.uleb((int) list.get(j + 1));
                }
            }
            for (int[] t : c.tries) {
                o.u4(t[0]);
                o.u2(t[1]);
                o.u2(offsets[t[2]]);
            }
            o.bytes(h.b, h.n);
        }

        /** A static field's starting value; the value's type tag must match the field's type. */
        static void writeValue(Out o, String type, Object v, Map<String, Integer> si) {
            long n = v instanceof Integer i ? i : v instanceof Long l ? l : v instanceof Float f ? Float.floatToRawIntBits(f)
                    : v instanceof Double x ? Double.doubleToRawLongBits(x) : 0;
            switch (type.charAt(0)) {
                case 'Z' -> o.u1(0x1f | (n != 0 ? 1 : 0) << 5);
                case 'B' -> { o.u1(0x00); o.u1((int) n); }
                case 'S' -> { o.u1(0x02 | 1 << 5); o.u2((int) n); }
                case 'C' -> { o.u1(0x03 | 1 << 5); o.u2((int) n); }
                case 'I' -> { o.u1(0x04 | 3 << 5); o.u4((int) n); }
                case 'F' -> { o.u1(0x10 | 3 << 5); o.u4((int) n); }
                case 'J' -> { o.u1(0x06 | 7 << 5); o.u8(n); }
                case 'D' -> { o.u1(0x11 | 7 << 5); o.u8(n); }
                default -> {
                    if (v == null) o.u1(0x1e);
                    else { o.u1(0x17 | 3 << 5); o.u4(si.get((String) v)); }
                }
            }
        }
    }
}
