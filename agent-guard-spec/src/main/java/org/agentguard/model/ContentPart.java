package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Arrays;

/**
 * 消息内容片段，支持多模态。目前定义文本与媒体两种，后续可追加子类。
 */
public abstract class ContentPart {

    ContentPart() {
    }

    public static Text text(String text) {
        return new Text(text);
    }

    public static Media media(String mimeType, String url) {
        return new Media(mimeType, url, null);
    }

    public static Media media(String mimeType, byte[] data) {
        return new Media(mimeType, null, data);
    }

    /** 文本片段。 */
    public static final class Text extends ContentPart {
        private final String text;

        private Text(String text) {
            this.text = Immutables.requireNonNull(text, "text");
        }

        public String text() {
            return text;
        }

        @Override
        public String toString() {
            return "Text{length=" + text.length() + '}';
        }
    }

    /** 媒体片段（图片、音频、文件等），url 与 data 二选一。 */
    public static final class Media extends ContentPart {
        private final String mimeType;
        private final String url;
        private final byte[] data;

        private Media(String mimeType, String url, byte[] data) {
            this.mimeType = mimeType;
            this.url = url;
            this.data = data == null ? null : Arrays.copyOf(data, data.length);
        }

        public String mimeType() {
            return mimeType;
        }

        public String url() {
            return url;
        }

        public byte[] data() {
            return data == null ? null : Arrays.copyOf(data, data.length);
        }

        @Override
        public String toString() {
            return "Media{mimeType=" + mimeType + ", url=" + url
                    + ", bytes=" + (data == null ? 0 : data.length) + '}';
        }
    }
}
