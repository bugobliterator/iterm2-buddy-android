import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks a companion plugin's JavaScript against its detached signature exactly as iTerm2 3.7.7
 * does (CompanionPlugin.checkSignature): Ed25519 over the file's raw bytes, the signature file
 * holding standard base64 with surrounding whitespace ignored, the key the 32-byte raw public key
 * iTerm2 embeds. Exits 0 when the signature is valid and 1 otherwise.
 *
 * Usage: java VerifyPluginSignature.java <raw public key, base64> <plugin .js> <plugin .sig>
 */
public final class VerifyPluginSignature {
    /** The DER prefix of an X.509 SubjectPublicKeyInfo for a raw 32-byte Ed25519 key (RFC 8410). */
    private static final byte[] ED25519_SPKI_PREFIX = {
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: java VerifyPluginSignature.java <public key base64> <plugin .js> <plugin .sig>");
            System.exit(2);
        }
        byte[] rawKey = Base64.getDecoder().decode(args[0]);
        if (rawKey.length != 32) throw new IllegalArgumentException("the public key is " + rawKey.length + " bytes, not 32");
        byte[] encoded = new byte[ED25519_SPKI_PREFIX.length + rawKey.length];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, encoded, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(rawKey, 0, encoded, ED25519_SPKI_PREFIX.length, rawKey.length);
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));

        byte[] message = Files.readAllBytes(Path.of(args[1]));
        String signatureText = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8).strip();
        byte[] signature = Base64.getDecoder().decode(signatureText);

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(message);
        boolean valid = verifier.verify(signature);
        System.out.println("VerifyPluginSignature: " + (valid ? "valid" : "INVALID")
            + " Ed25519 signature over " + message.length + " bytes");
        System.exit(valid ? 0 : 1);
    }
}
