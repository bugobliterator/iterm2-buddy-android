import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.HexFormat;
import javax.crypto.KeyAgreement;

/**
 * Mints a throwaway pairing for the real iTerm2 CI job: a Mac X25519 static key pair, a phone
 * X25519 static key pair, a 32-byte relay room secret and a 16-hex-digit pairing ID (the shape
 * iTerm2's makePairingID produces). Writes them as KEY=value lines to a new file readable only by
 * its owner and prints nothing secret.
 *
 * Usage: java GenerateCiPairing.java <output file> <relay origin>
 *
 * A private key is 32 random bytes, as CryptoKit's Curve25519 rawRepresentation and noise-java
 * take it (both clamp on use). Its public key is X25519(private, 9), the base point's u = 9.
 */
public final class GenerateCiPairing {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws IOException, GeneralSecurityException {
        if (args.length != 2) {
            System.err.println("usage: java GenerateCiPairing.java <output file> <relay origin>");
            System.exit(2);
        }
        Path output = Path.of(args[0]);
        String relayOrigin = args[1];
        byte[] macPrivate = randomBytes(32);
        byte[] phonePrivate = randomBytes(32);
        String text = "# Throwaway pairing for the real iTerm2 CI job, minted for this run only.\n"
            + "PAIRING_ID=" + HEX.formatHex(randomBytes(8)) + "\n"
            + "RELAY_ORIGIN=" + relayOrigin + "\n"
            + "MAC_STATIC_PRIVATE_KEY_HEX=" + HEX.formatHex(macPrivate) + "\n"
            + "MAC_STATIC_PUBLIC_KEY_HEX=" + HEX.formatHex(publicKey(macPrivate)) + "\n"
            + "PHONE_STATIC_PRIVATE_KEY_HEX=" + HEX.formatHex(phonePrivate) + "\n"
            + "PHONE_STATIC_PUBLIC_KEY_HEX=" + HEX.formatHex(publicKey(phonePrivate)) + "\n"
            + "ROOM_SECRET_HEX=" + HEX.formatHex(randomBytes(32)) + "\n";
        Files.deleteIfExists(output);
        Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(output, text, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("GenerateCiPairing: wrote a Mac key pair, a phone key pair, a room secret and a pairing ID to " + output);
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** X25519(private, 9): the 32-byte little-endian u-coordinate of the public key. */
    static byte[] publicKey(byte[] privateKey) throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("X25519");
        PrivateKey key = factory.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey));
        PublicKey basePoint = factory.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)));
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(key);
        agreement.doPhase(basePoint, true);
        byte[] publicKey = agreement.generateSecret();
        if (publicKey.length != 32) throw new GeneralSecurityException("X25519 public key is " + publicKey.length + " bytes");
        return publicKey;
    }
}
