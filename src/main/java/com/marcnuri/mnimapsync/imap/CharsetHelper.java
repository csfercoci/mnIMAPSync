package com.marcnuri.mnimapsync.imap;


import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class CharsetHelper {
    private static final int MAX_SAMPLE_SIZE = 8192;
    private static String CHINESE = new Locale("zh").getLanguage();
    private static final List<String> COMMON = Collections.unmodifiableList(Arrays.asList(
            "US-ASCII",
            "ISO-8859-1", "ISO-8859-2", "ISO-8859-3", "ISO-8859-7",
            "windows-1250", "windows-1251", "windows-1252", "windows-1255", "windows-1256", "windows-1257",
            "UTF-8"
    ));
    private static final List<String> LESS_COMMON = Collections.unmodifiableList(Arrays.asList(
            "GBK", "GB2312", "HZ-GB-2312",
            "EUC", "EUC-KR",
            "Big5", "BIG5-CP950",
            "ISO-2022-JP", "Shift_JIS",
            "cp852",
            "KOI8-R",
            "x-binaryenc"
    ));
    private static final int MIN_W1252 = 10;


    private static native DetectResult jni_detect_charset(byte[] octets, String ref, String lang);

    static boolean isUTF8(String text) {
        // Get extended ASCII characters
        byte[] octets = text.getBytes(StandardCharsets.ISO_8859_1);
        return isUTF8(octets);
    }

    static boolean isUTF8(byte[] octets) {
        return isValid(octets, StandardCharsets.UTF_8);
    }

    static boolean isUTF16(byte[] octets) {
        return isValid(octets, StandardCharsets.UTF_16);
    }

    static Boolean isValid(byte[] octets, String charset) {
        return isValid(octets, Charset.forName(charset));
    }

    static boolean isValid(byte[] octets, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(octets));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }

    static boolean isUTF8Alt(String text) {
        // This doesn't check the characters and is therefore unreliable
        byte[] octets = text.getBytes(StandardCharsets.ISO_8859_1);

        int bytes;
        for (int i = 0; i < octets.length; i++) {
            if ((octets[i] & 0b10000000) == 0b00000000)
                bytes = 1;
            else if ((octets[i] & 0b11100000) == 0b11000000)
                bytes = 2;
            else if ((octets[i] & 0b11110000) == 0b11100000)
                bytes = 3;
            else if ((octets[i] & 0b11111000) == 0b11110000)
                bytes = 4;
            else if ((octets[i] & 0b11111100) == 0b11111000)
                bytes = 5;
            else if ((octets[i] & 0b11111110) == 0b11111100)
                bytes = 6;
            else
                return false;

            if (i + bytes > octets.length)
                return false;

            while (--bytes > 0)
                if ((octets[++i] & 0b11000000) != 0b10000000)
                    return false;
        }
        return true;
    }

    static Boolean isUTF16LE(BufferedInputStream bis) throws IOException {
        // https://en.wikipedia.org/wiki/Endianness
        byte[] bytes = new byte[64];
        bis.mark(bytes.length);
        try {
            int count = bis.read(bytes);
            if (count < 32)
                return null;

            int s = ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff);
            boolean bom = (s == 0xfeff || s == 0xfffe);
            if (bom)
                return null;

            int odd = 0;
            int even = 0;
            for (int i = 0; i < count; i++)
                if (bytes[i] == 0)
                    if (i % 2 == 0)
                        even++;
                    else
                        odd++;

            int low = 30 * count / 100 / 2;
            int high = 70 * count / 100 / 2;

            if (even < low && odd > high)
                return true; // Little endian
            if (odd < low && even > high)
                return false; // Big endian
            return null; // Undetermined
        } finally {
            bis.reset();
        }
    }


    public static Charset detect(String text, Charset ref) {
        if (text == null)
            return null;

        try {
            byte[] octets = text.getBytes(StandardCharsets.ISO_8859_1);

            byte[] sample;
            if (octets.length < MAX_SAMPLE_SIZE)
                sample = octets;
            else {
                sample = new byte[MAX_SAMPLE_SIZE];
                System.arraycopy(octets, 0, sample, 0, MAX_SAMPLE_SIZE);
            }


            DetectResult detected = jni_detect_charset(sample,
                    ref == null ? StandardCharsets.ISO_8859_1.name() : ref.name(),
                    Locale.getDefault().getLanguage());

            if (detected.charset == null) {
                return null;
            } else if (COMMON.contains(detected.charset) || LESS_COMMON.contains(detected.charset)) {
            } else if ("UTF-7".equals(detected.charset))
                return null;
            else if ("GB18030".equals(detected.charset)) {
                boolean chinese = Locale.getDefault().getLanguage().equals(CHINESE);
                // https://github.com/google/compact_enc_det/issues/8
                if (!chinese)
                    return null;
            }

            return Charset.forName(detected.charset);
        } catch (Throwable ex) {
            return null;
        }
    }

    private static class DetectResult {
        String charset;
        int sample_size;
        int bytes_consumed;
        boolean is_reliable;

        DetectResult(String charset, int sample_size, int bytes_consumed, boolean is_reliable) {
            this.charset = charset;
            this.sample_size = sample_size;
            this.bytes_consumed = bytes_consumed;
            this.is_reliable = is_reliable;
        }

        @Override
        public String toString() {
            return charset + " s=" + bytes_consumed + "/" + sample_size + " r=" + is_reliable;
        }
    }
}
