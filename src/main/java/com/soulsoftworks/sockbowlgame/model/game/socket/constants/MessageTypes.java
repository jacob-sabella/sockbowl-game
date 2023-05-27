package com.soulsoftworks.sockbowlgame.model.game.socket.constants;

public class MessageTypes {

    public static class In {


        public static class Config {
            public static String UPDATE_PLAYER_TEAM = "UPDATE_PLAYER_TEAM";
        }

        public static String REQUEST_STATE = "REQUEST_STATE";

    }

    public static class Out {
        public static String PROCESS_ERROR = "PROCESS_ERROR";
    }

}
