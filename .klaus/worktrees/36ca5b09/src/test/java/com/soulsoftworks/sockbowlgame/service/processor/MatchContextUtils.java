package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.Team;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public  class MatchContextUtils {

    public static List<Player> createPlayers(int numberOfPlayers) {
        return IntStream.rangeClosed(1, numberOfPlayers)
                .mapToObj(i -> {
                    Player player = Player.builder()
                            .playerId("TEST-PLAYER-" + i + "-ID")
                            .playerSecret("TEST-PLAYER-" + i + "-SECRET")
                            .build();
                    if(i == 1){
                        player.setGameOwner(true);
                    }

                    return player;
                })
                .collect(Collectors.toList());
    }

    public static List<Team> createTeams(int numberOfTeams) {
        return IntStream.rangeClosed(1, numberOfTeams)
                .mapToObj(i -> {
                    Team team = new Team();
                    team.setTeamId("TEST-TEAM-" + i);
                    return team;
                })
                .collect(Collectors.toList());
    }
}
