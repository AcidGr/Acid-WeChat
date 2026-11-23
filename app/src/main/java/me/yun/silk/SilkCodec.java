package me.yun.silk;

/**
 * JNI bridge for `libsilk_codec.so` (SILK v3 / MP3 / WAV / FLAC / OGG / PCM transcoding).
 * Method names and signatures must stay un-obfuscated to match native JNI exports.
 */
public class SilkCodec {
    public static final long MAX_DURATION_MS = 60_000L;

    public native int getFileType(String filePath);

    public native int mp3ToSilk(String mp3Path, String silkPath, int hz);

    public native int wavToSilk(String wavPath, String silkPath, int hz);

    public native int flacToSilk(String flacPath, String silkPath, int hz);

    public native int oggToSilk(String oggPath, String silkPath, int hz);

    public native int pcmToSilk(String pcmPath, String silkPath, int hz, int pcmHz, int channels);

    public native int autoToSilk(String audioPath, String silkPath, int hz);

    public native int silkToMp3(String silkPath, String mp3Path, int hz);

    public native int silkToPcm(String silkPath, String pcmPath, int hz);

    public native int mp3ToPcm(String mp3Path, String pcmPath);

    public native int wavToPcm(String wavPath, String pcmPath);

    public native int flacToPcm(String flacPath, String pcmPath);

    public native int oggToPcm(String oggPath, String pcmPath);

    public native int autoToPcm(String audioPath, String pcmPath);

    public native long getDuration(String filePath);
}
