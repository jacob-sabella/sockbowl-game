package com.soulsoftworks.sockbowlgame.util;


import com.google.gson.Gson;

import java.lang.reflect.Type;

public class DeepCopyUtil {

    private static final Gson gson = new Gson();

    public static <T> T deepCopy(T object, Class<T> clazz) {
        // Serialize the object to JSON and then deserialize it
        return gson.fromJson(gson.toJson(object), clazz);
    }

    // New method to handle generic types
    public static <T> T deepCopy(T object, Type typeOfT) {
        return gson.fromJson(gson.toJson(object), typeOfT);
    }
}
