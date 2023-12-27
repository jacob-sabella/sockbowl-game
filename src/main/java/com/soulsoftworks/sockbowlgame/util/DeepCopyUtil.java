package com.soulsoftworks.sockbowlgame.util;


import com.google.gson.Gson;

public class DeepCopyUtil {

    private static final Gson gson = new Gson();

    public static <T> T deepCopy(T object, Class<T> clazz) {
        // Serialize the object to JSON and then deserialize it
        return gson.fromJson(gson.toJson(object), clazz);
    }
}
