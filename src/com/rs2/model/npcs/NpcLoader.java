package com.rs2.model.npcs;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import com.rs2.Constants;
import com.rs2.model.Entity;
import com.rs2.model.Graphic;
import com.rs2.model.Position;
import com.rs2.model.World;
import com.rs2.model.content.Following;
import com.rs2.model.content.combat.CombatManager;
import com.rs2.model.npcs.Npc.WalkType;
import com.rs2.model.players.Player;

/**
 * Having anything to do with any type of npc data loading.
 * 
 * @author BFMV
 */
public class NpcLoader {

	/**
	 * Loads auto-spawn file
	 **/
	public static boolean loadAutoSpawn(String FileName) {
		String line = "";
		String token = "";
		String token2 = "";
		String token2_2 = "";
		String[] token3 = new String[10];
		boolean EndOfFile = false;
		BufferedReader characterfile = null;
		try {
			characterfile = new BufferedReader(new FileReader("./" + FileName));
		} catch (FileNotFoundException fileex) {
			System.out.println(FileName + ": file not found.");
			return false;
		}
		try {
			line = characterfile.readLine();
		} catch (IOException ioexception) {
			System.out.println(FileName + ": error loading file.");
			return false;
		}
		while (EndOfFile == false && line != null) {
			line = line.trim();
			int spot = line.indexOf("=");
			if (spot > -1) {
				token = line.substring(0, spot);
				token = token.trim();
				token2 = line.substring(spot + 1);
				token2 = token2.trim();
				token2_2 = token2.replaceAll("\t\t", "\t");
				token2_2 = token2_2.replaceAll("\t\t", "\t");
				token2_2 = token2_2.replaceAll("\t\t", "\t");
				token2_2 = token2_2.replaceAll("\t\t", "\t");
				token2_2 = token2_2.replaceAll("\t\t", "\t");
				token3 = token2_2.split("\t");
				if (token.equals("spawn")) {
					newNPC(Integer.parseInt(token3[0]), Integer.parseInt(token3[1]), Integer.parseInt(token3[2]), Integer.parseInt(token3[3]), Integer.parseInt(token3[4]));
				}
			} else if (line.equals("[ENDOFSPAWNLIST]")) {
				try {
					characterfile.close();
				} catch (IOException ioexception) {
				}
				return true;
			}
			try {
				line = characterfile.readLine();
			} catch (IOException ioexception1) {
				EndOfFile = true;
				System.out.println("Loaded all npc spawns.");
			}
		}
		try {
			characterfile.close();
		} catch (IOException ioexception) {
		}
		return false;
	}

	public static void newNPC(int id, int x, int y, int heightLevel, int face) {
		Npc npc = new Npc(id);
		npc.setPosition(new Position(x, y, heightLevel));
		npc.setSpawnPosition(new Position(x, y, heightLevel));
        npc.setNeedsRespawn(true);
		npc.setMinWalk(new Position(x - Constants.NPC_WALK_DISTANCE, y - Constants.NPC_WALK_DISTANCE));
		npc.setMaxWalk(new Position(x + Constants.NPC_WALK_DISTANCE, y + Constants.NPC_WALK_DISTANCE));
        npc.setWalkType(face == 1 || face > 5 ? WalkType.WALK : WalkType.STAND);
		npc.setFace(face);
		npc.setCurrentX(x);
		npc.setCurrentY(y);
        npc.setNeedsRespawn(true);
		World.register(npc);

	}

	public static void spawnNpc(Player player, Npc npc, boolean attack, boolean hintIcon) {
		int x = 0, y = 0;
		if (player.canMove(1, 0)) {
			x = 1;
			y = 0;
		} else if (player.canMove(-1, 0)) {
			x = -1;
			y = 0;
		} else if (player.canMove(0, 1)) {
			x = 0;
			y = 1;
		} else if (player.canMove(0, -1)) {
			x = 0;
			y = -1;
		}
		x = player.getPosition().getX() + x;
		y = player.getPosition().getY() + y;
		npc.setPosition(new Position(x, y, player.getPosition().getZ()));
		npc.setSpawnPosition(new Position(x, y, player.getPosition().getZ()));
		npc.setWalkType(Npc.WalkType.STAND);
		npc.setCurrentX(x);
		npc.setCurrentY(y);
		World.register(npc);
		player.setSpawnedNpc(npc);
		npc.setPlayerOwner(player.getIndex());
		npc.getUpdateFlags().sendFaceToDirection(player.getPosition());
		if (attack)
			CombatManager.attack(npc, player);
        else {
            npc.setFollowDistance(1);
            npc.setFollowingEntity(player);
        }
        if(hintIcon)
            player.getActionSender().createPlayerHints(1, (npc).getIndex());
	    if (npc.getNpcId() == 77) {
	    	npc.getUpdateFlags().sendGraphic(Graphic.lowGraphic(78));
	    }
	}

	public static boolean checkSpawn(Player player, int id) {
		return player.getSpawnedNpc() != null && !player.getSpawnedNpc().isDead() && player.getSpawnedNpc().getNpcId() == id;
	}

	public static void spawnNpc(Entity entityToAttack, Npc npc, Position spawningPosition, boolean hintIcon, String message) {
		npc.setPosition(spawningPosition);
		npc.setWalkType(Npc.WalkType.STAND);
		npc.setCurrentX(spawningPosition.getX());
		npc.setCurrentY(spawningPosition.getY());
        npc.setNeedsRespawn(false);
		World.register(npc);
		if (entityToAttack != null){
			npc.setFollowingEntity(entityToAttack);
            CombatManager.attack(npc, entityToAttack);
		    npc.getUpdateFlags().sendFaceToDirection(entityToAttack.getPosition());
        }
        if(entityToAttack.isPlayer() && hintIcon)
            ((Player)entityToAttack).getActionSender().createPlayerHints(1, (npc).getIndex());
	    if(message != null)
            npc.getUpdateFlags().sendForceMessage(message);
    }

	public static void destroyNpc(Npc npc) {
		if (npc.getPlayerOwner() != null) {
			npc.getPlayerOwner().setSpawnedNpc(null);
		}
		npc.setVisible(false);
		Following.resetFollow(npc);
		World.unregister(npc);
	}

}
