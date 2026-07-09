package sashwind.mc.mod.ffcraft.client;

import sashwind.mc.mod.ffcraft.client.player.Player;

import java.util.ArrayList;
import java.util.List;

public class DataKeeper {

    public static boolean createplayer_line = false;
    public static int createplayer_pointCount = 0;

    public static double pos_precision = (double) 1 / 16 ;//16


    public static List<Player> players = new ArrayList<>();
}
