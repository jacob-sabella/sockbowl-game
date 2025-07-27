package com.soulsoftworks.sockbowlgame.controller.helper;

import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.GsonMessageConverter;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.ClassUtils;
import org.springframework.util.MimeType;

import java.util.List;

public class GsonMessageConverterWithStringResponse extends GsonMessageConverter {

    @Override
    public List<MimeType> getSupportedMimeTypes() {
        MimeType mimeType1 = new MimeType("application", "json");
        MimeType mimeType2 = new MimeType("text", "plain");
        return List.of(mimeType1, mimeType2);
    }

    @Override
    @Nullable
    protected Object convertFromInternal(Message<?> message, Class<?> targetClass, @Nullable Object conversionHint) {
        try {
            Object payload = message.getPayload();
            if (ClassUtils.isAssignableValue(targetClass, payload)) {
                return payload;
            }
            else if (payload instanceof byte[]) {
                    return new String((byte[]) payload);
            }
            else {
                // Assuming a text-based source payload
                return super.convertFromInternal(message, targetClass, conversionHint);
            }
        }
        catch (Exception ex) {
            throw new MessageConversionException(message, "Could not read JSON: " + ex.getMessage(), ex);
        }
    }
}
