// The build steps the Android SDK's aapt2 and apksigner normally do, using only the JDK. Run by build.sh.
//
// Usage: java Build.java manifest <AndroidManifest.xml> <image dir> <out dir>
//          Compiles AndroidManifest.xml into Android's binary XML format, and the .png files directly in
//          <image dir> into resources.arsc so the manifest can refer to them as @drawable/<name>.
//        java Build.java apk <dir> <out.apk> <keystore.p12> <alias>   (keystore password from KEYSTORE_PASS)
//          Packs a directory into an APK and signs it with APK Signature Scheme v2.

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public class Build {
    public static void main(String[] args) throws Exception {
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (args.length == 0 ? "" : args[0]) {
            case "manifest" -> Manifest.run(rest);
            case "apk" -> Apk.run(rest);
            default -> {
                System.err.println("usage: java Build.java manifest|apk <args>  (see the top of Build.java)");
                System.exit(2);
            }
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

        static void run(String[] args) throws Exception {
            if (args.length != 3) {
                System.err.println("usage: java Build.java manifest <AndroidManifest.xml> <image dir> <out dir>");
                System.exit(2);
            }
            Path outDir = Path.of(args[2]);

            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Element root = f.newDocumentBuilder().parse(new File(args[0])).getDocumentElement();

            // .png files in the image dir are copied into the APK's res/drawable/ and listed in resources.arsc
            // as @drawable/<name>. Subfolders and other files (like the app's Java source) are ignored.
            List<String> keys = new ArrayList<>(), paths = new ArrayList<>();
            List<Path> images;
            try (var list = Files.list(Path.of(args[1]))) {
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

        static void run(String[] args) throws Exception {
            if (args.length != 4) {
                System.err.println("usage: java Build.java apk <dir> <out.apk> <keystore.p12> <alias>");
                System.exit(2);
            }
            String pass = System.getenv("KEYSTORE_PASS");
            if (pass == null) {
                System.err.println("KEYSTORE_PASS is not set");
                System.exit(2);
            }

            // ZIP entries and central directory
            ByteArrayOutputStream entries = new ByteArrayOutputStream();
            ByteArrayOutputStream centralDir = new ByteArrayOutputStream();
            List<Path> files;
            Path root = Path.of(args[0]);
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
            char[] password = pass.toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = new FileInputStream(args[2])) {
                ks.load(in, password);
            }
            PrivateKey key = (PrivateKey) ks.getKey(args[3], password);
            X509Certificate cert = (X509Certificate) ks.getCertificate(args[3]);
            if (key == null || cert == null) {
                System.err.println("alias '" + args[3] + "' not found in " + args[2]);
                System.exit(1);
            }
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

            try (OutputStream out = new FileOutputStream(args[1])) {
                out.write(zipEntries);
                out.write(signingBlock);
                out.write(zipCentralDir);
                out.write(endRecord(count, zipCentralDir.length, zipEntries.length + signingBlock.length));
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
}
