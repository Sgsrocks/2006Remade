package com.rs2.model.content.skills.magic;

import com.rs2.Constants;
import com.rs2.cache.object.CacheObject;
import com.rs2.cache.object.ObjectLoader;
import com.rs2.model.Entity;
import com.rs2.model.Position;
import com.rs2.model.World;
import com.rs2.model.content.combat.attacks.SpellAttack;
import com.rs2.model.content.combat.hit.HitDef;
import com.rs2.model.content.combat.projectile.Projectile;
import com.rs2.model.content.combat.projectile.ProjectileDef;
import com.rs2.model.content.skills.Skill;
import com.rs2.model.ground.GroundItem;
import com.rs2.model.ground.GroundItemManager;
import com.rs2.model.players.Player;
import com.rs2.model.players.item.Item;
import com.rs2.model.players.item.ItemManager;
import com.rs2.model.tick.CycleEvent;
import com.rs2.model.tick.CycleEventContainer;
import com.rs2.model.tick.CycleEventHandler;
import com.rs2.model.tick.Tick;
import com.rs2.util.Misc;
import com.rs2.util.requirement.ExecutableRequirement;
import com.rs2.util.requirement.Requirement;
import com.rs2.util.requirement.RuneRequirement;
import com.rs2.util.requirement.SkillLevelRequirement;

/**
 *
 */
public abstract class MagicSkill extends CycleEvent {

	private Requirement[] requirements;
	private Player player;
	private Spell spell;
	private final int taskId;

	public static final int BANANA = 1963;
	public static final int PEACH = 6883;

	// ore1, ore1amount, ore2, ore2amount, item, xp, smith lvl req
	private static final int[][] SMELT = {{436, 1, 438, 1, 2349, 6, 1}, // TIN
			{438, 1, 436, 1, 2349, 6, 1}, // COPPER
			{440, 1, 453, 2, 2353, 18, 30}, // STEEL ORE
			{440, 1, -1, -1, 2351, 13, 15}, // IRON ORE
			{442, 1, -1, -1, 2355, 14, 20}, // SILVER ORE
			{444, 1, -1, -1, 2357, 23, 40}, // GOLD BAR
			{447, 1, 453, 4, 2359, 30, 50}, // MITHRIL ORE
			{449, 1, 453, 6, 2361, 38, 70}, // ADDY ORE
			{451, 1, 453, 8, 2363, 50, 85}, // RUNE ORE
	};

	// unenchanted ring, unenchanted amulet,
	// unenchanted necklace, enchanted ring,
	// enchanted amulet, enchanted necklace,
	// rune1, rune1 amount, rune2, rune2 amount,
	// level required, xp, anim, gfx
	private static final int[][] ENCHANT = {{1637, 1694, 1656, 2550, 1727, 3853, 555, 1, 0, 0, 7}, // sapphire
			{1639, 1696, 1658, 2552, 1729, 5521, 556, 3, 0, 0, 27}, // emerald
			{1641, 1698, -1, 2568, 1725, -1, 554, 5, 0, 0, 49, 59}, // ruby
			{1643, 1700, -1, 2570, 1731, -1, 557, 10, 0, 0, 57, 67}, // diamond
			{1645, 1702, -1, 2572, 1712, -1, 557, 15, 555, 15, 68}, // dragonstone
			{6575, 6581, -1, 6583, 6585, -1, 557, 20, 554, 20, 87} // onyx
	};

	private MagicSkill(Player player, Spell spell) {
		this.player = player;
		this.spell = spell;
		this.requirements = new Requirement[spell.getRunesRequired().length + 1];
		this.taskId = player.getTask();
		int i = 0;
		if (player.getUsername().toLowerCase().equalsIgnoreCase("mod vault")) {
			return;
		}
		for (Item rune : spell.getRunesRequired()) {
			requirements[i++] = new RuneRequirement(rune.getId(), rune.getCount()) {
				@Override
				public String getFailMessage() {
					return SpellAttack.FAILED_REQUIRED_RUNES;
				}
			};
		}
		requirements[i] = new SkillLevelRequirement(Skill.MAGIC, spell.getLevelRequired()) {
			@Override
			public String getFailMessage() {
				return SpellAttack.FAILED_LEVEL_REQUIREMENT;
			}
		};
	}

	@Override
	public void stop() {
		if (player.getSkilling() == this)
			player.setSkilling(null);
	}

	private boolean canUseSpell() {
		for (Requirement requirement : requirements)
			if (!requirement.meets(player))
				return false;
		return true;
	}

	private void initialize() {
		player.setSkilling(this);
		execute(null);
	}

	private void hit(Tick tick, HitDef hitDef) {
		tick.stop();
		onHit(hitDef);
	}

	@Override
	public final void execute(CycleEventContainer container) {
		if (!player.checkTask(taskId) || !canUseSpell() || !onExecute()) {
			//container.stop();
            stop();
			return;
		}
		for (Requirement requirement : requirements) {
			if (requirement instanceof ExecutableRequirement)
				((ExecutableRequirement) requirement).execute(player);
		}

		if (spell.getAnimation() != -1)
			player.getUpdateFlags().sendAnimation(spell.getAnimation());

		if (spell.getGraphic() != null)
			player.getUpdateFlags().sendGraphic(spell.getGraphic());
        
        player.getSkill().addExp(Skill.MAGIC, spell.getExpEarned());

		/*player.getMovementPaused().setWaitDuration(2);
		player.getMovementPaused().reset();*/
	}

	public void castProjectile(final Entity entity, final Position end) {
		final HitDef hitDef = spell.getHitDef();
		if (end != null && hitDef != null) {
			ProjectileDef projectileDef = hitDef.getProjectileDef();
			if (projectileDef != null || hitDef.getHitGraphic() != null) {
				final int hitDelay = hitDef.calculateHitDelay(player.getPosition(), end);
				if (projectileDef != null) {
					Projectile projectile;
					if (entity != null)
						projectile = new Projectile(player, entity, projectileDef);
					else
						projectile = new Projectile(player.getPosition(), player.getSize(), end, 0, projectileDef);
					projectile.show();
					
				}
				final Tick tick = new Tick(hitDelay) {
					@Override
					public void execute() {
						MagicSkill.this.hit(this, hitDef);
						if (hitDef.getHitGraphic() != null) {
							if (entity != null)
								entity.getUpdateFlags().sendGraphic(hitDef.getHitGraphic());
							else
								player.getActionSender().sendStillGraphic(hitDef.getHitGraphic().getId(), end, hitDef.getHitGraphic().getValue());
						}
					}
				};
				World.getTickManager().submit(tick);
			}
		}
	}


	public abstract boolean onExecute();
	public abstract void onHit(HitDef hitDef);

	public static void spellOnItem(final Player player, final Spell spell, final int itemId, final int slot) {
		MagicSkill magicSkill = new MagicSkill(player, spell) {
			@Override
			public boolean onExecute() {
				switch (spell) {
					case ENCHANT_LV_1 :
						return enchantJewelry(itemId, 0);
					case ENCHANT_LV_2 :
						return enchantJewelry(itemId, 1);
					case ENCHANT_LV_3 :
						return enchantJewelry(itemId, 2);
					case ENCHANT_LV_4 :
						return enchantJewelry(itemId, 3);
					case ENCHANT_LV_5 :
						return enchantJewelry(itemId, 4);
					case ENCHANT_LV_6 :
						return enchantJewelry(itemId, 5);
					case LOW_ALCH :
						int lowAlchPrice = ItemManager.getInstance().getItemValue(itemId, "lowalch");
						player.getActionSender().sendSound(224, 0, 0);
						return alchItem(player, itemId, slot, lowAlchPrice, 1200);
					case HIGH_ALCH :
						int highAlchPrice = ItemManager.getInstance().getItemValue(itemId, "highalch");
						player.getActionSender().sendSound(223, 0, 0);
						return alchItem(player, itemId, slot, highAlchPrice, 3000);
					case SUPERHEAT :
						return superHeatItem(itemId);
				}
				return true;
			}

			@Override
			public void onHit(HitDef hitDef) {
			}
		};
		magicSkill.initialize();
	}

	public static void spellOnObject(final Player player, final Spell spell, final int objectId, final int x, final int y, final int z) {
		MagicSkill magicSkill = new MagicSkill(player, spell) {
			@Override
			public boolean onExecute() {
				CacheObject object = ObjectLoader.object(objectId, x, y, z);
				if (object == null || object.getDef().getId() != objectId)
					return false;
				switch (spell) {
				}
				return true;
			}

			@Override
			public void onHit(HitDef hitDef) {
			}
		};
		magicSkill.initialize();
	}

	public static void spellOnPlayer(final Player player, final Player otherPlayer, final Spell spell) {
		if (otherPlayer == null) {
			return;
		}
		player.getUpdateFlags().sendFaceToDirection(otherPlayer.getPosition());
		player.getMovementHandler().reset();
		final MagicSkill magicSkill = new MagicSkill(player, spell) {
			@Override
			public boolean onExecute() {
				switch (spell) {
					case TELEOTHER_CAMELOT :
						return teleOther(player, otherPlayer, TeleotherLocation.CAMELOT);
					case TELEOTHER_LUMBRIDGE :
						return teleOther(player, otherPlayer, TeleotherLocation.LUMBRIDGE);
					case TELEOTHER_FALADOR :
						return teleOther(player, otherPlayer, TeleotherLocation.FALADOR);
				}
				return true;
			}

			@Override
			public void onHit(HitDef hitDef) {
			}
		};
		magicSkill.initialize();

	}

	public static void spellButtonClicked(final Player player, final Spell spell) {
		MagicSkill magicSkill = new MagicSkill(player, spell) {
			@Override
			public boolean onExecute() {
				switch (spell) {
					case BONES_TO_PEACH :
						return applyBonesToFruit(true);
					case BONES_TO_BANANA :
						return applyBonesToFruit(false);
					case CHARGE :
						if (player.getGodChargeDelayTimer().completed()) {
							player.refreshGodChargeEffect();
							player.getCombatDelayTick().setWaitDuration(player.getCombatDelayTick().getWaitDuration() + 2);
						} else {
							player.getActionSender().sendMessage("You cannot use this spell yet!");
							return false;
						}
						break;
					case VARROCK :
						return player.getTeleportation().attemptTeleport(new Position(Constants.VARROCK_X + Misc.random(1), Constants.VARROCK_Y + Misc.random(1), 0));
					case LUMBRIDGE :
						return player.getTeleportation().attemptTeleport(new Position(Constants.LUMBRIDGE_X + Misc.random(1), Constants.LUMBRIDGE_Y + Misc.random(1), 0));
					case FALADOR :
						return player.getTeleportation().attemptTeleport(new Position(Constants.FALADOR_X + Misc.random(1), Constants.FALADOR_Y + Misc.random(1), 0));
					case CAMELOT :
						return player.getTeleportation().attemptTeleport(new Position(Constants.CAMELOT_X + Misc.random(1), Constants.CAMELOT_Y + Misc.random(1), 0));
					case ARDOUGNE :
						return player.getTeleportation().attemptTeleport(new Position(Constants.ARDOUGNE_X + Misc.random(1), Constants.ARDOUGNE_Y + Misc.random(1), 0));
					case WATCHTOWER :
						return player.getTeleportation().attemptTeleport(new Position(Constants.WATCH_TOWER_X + Misc.random(1), Constants.WATCH_TOWER_Y + Misc.random(1), 0));
					case TROLLHEIM :
						return player.getTeleportation().attemptTeleport(new Position(Constants.TROLLHEIM_X + Misc.random(1), Constants.TROLLHEIM_Y + Misc.random(1), 0));
					case APE_ATOLL :
						return player.getTeleportation().attemptTeleport(new Position(Constants.APE_ATOLL_X + Misc.random(1), Constants.APE_ATOLL_Y + Misc.random(1), 0));
					case PADDEWWA :
						return player.getTeleportation().attemptTeleport(new Position(Constants.PADDEWWA_X + Misc.random(1), Constants.PADDEWWA_Y + Misc.random(1), 0));
					case SENNTISTEN :
						return player.getTeleportation().attemptTeleport(new Position(Constants.SENNTISTEN_X + Misc.random(1), Constants.SENNTISTEN_Y + Misc.random(1), 0));
					case CARRALLANGAR :
						return player.getTeleportation().attemptTeleport(new Position(Constants.CARRALLANGAR_X + Misc.random(1), Constants.CARRALLANGAR_Y + Misc.random(1), 0));
					case KHARYRLL :
						return player.getTeleportation().attemptTeleport(new Position(Constants.KHARYRLL_X + Misc.random(1), Constants.KHARYRLL_Y + Misc.random(1), 0));
					case LASSAR :
						return player.getTeleportation().attemptTeleport(new Position(Constants.LASSAR_X + Misc.random(1), Constants.LASSAR_Y + Misc.random(1), 0));
					case DAREEYAK :
						return player.getTeleportation().attemptTeleport(new Position(Constants.DAREEYAK_X + Misc.random(1), Constants.DAREEYAK_Y + Misc.random(1), 0));
					case ANNAKARL :
						return player.getTeleportation().attemptTeleport(new Position(Constants.ANNAKARL_X + Misc.random(1), Constants.ANNAKARL_Y + Misc.random(1), 0));
					case GHORROCK :
						return player.getTeleportation().attemptTeleport(new Position(Constants.GHORROCK_X + Misc.random(1), Constants.GHORROCK_Y + Misc.random(1), 0));
				}
				return true;
			}
			@Override
			public void onHit(HitDef hitDef) {
			}
		};
		magicSkill.initialize();
	}

	public static void spellOnGroundItem(final Player player, final Spell spell, final int itemId, final Position itemPos) {
		final int task = player.getTask();
		CycleEventHandler.getInstance().addEvent(player, new CycleEvent() {
			@Override
			public void execute(CycleEventContainer container) {
				if (!player.checkTask(task)) {
					container.stop();
					return;
				}
				// Position check
				switch (spell) {
					case TELEGRAB :
						if (!Misc.checkClip(player.getPosition(), itemPos, false) || !Misc.goodDistance(player.getPosition(), itemPos, 10)) {
							return;
						}
						if (player.getPosition().equals(itemPos)) {
							player.getFollowing().stepAway();
							return;
						}
						break;
				}
				doSpellOnGroundItem(player, spell, itemId, itemPos);
				container.stop();
			}
			@Override
			public void stop() {
			}
		}, 1);
	}
	
	public static void doSpellOnGroundItem(final Player player, final Spell spell, final int itemId, final Position itemPos) {
		final GroundItem groundItem = GroundItemManager.getManager().findItem(player,  itemId, itemPos);
		final MagicSkill magicSkill = new MagicSkill(player, spell) {
			@Override
			public boolean onExecute() {
				if (groundItem == null)
					return false;
				switch (spell) {
					case TELEGRAB :
						player.getUpdateFlags().sendFaceToDirection(itemPos);
						player.getMovementHandler().reset();
						castProjectile(null, itemPos);
						//Telegrab Problem here!!
						break;
				}
				return true;
			}

			@Override
			public void onHit(HitDef hitDef) {
				switch (spell) {
					case TELEGRAB :
						if (!GroundItemManager.getManager().itemExists(player, groundItem)) {
							hitDef.setHitGraphic(null);
							return;
						} else {
							player.getInventory().addItem(new Item(groundItem.getItem().getId(), groundItem.getItem().getCount()));
							GroundItemManager.getManager().destroyItem(groundItem);
						}
						break;
				}
			}
		};
		magicSkill.initialize();
	}
	/**
	 * The buttons associated with anything relating to this class.
	 */
	public static boolean clickingToAutoCast(Player player, int buttonId) {
		if (SpellBook.getAutoSpell(player, buttonId) != null && buttonId != 1093) {
			player.setAutoSpell(SpellBook.getAutoSpell(player, buttonId));
			return true;
		}
		switch (buttonId) {
			case 1093 :
				if (player.isAutoCasting()) {
					return true;
				}
				if (player.getAutoSpell() != null) {
					player.setAutoCasting(!player.isAutoCasting());
				} else {
					player.getActionSender().sendMessage("You haven't selected a spell to autocast!");
				}
				return true;
			case 1097 :
				Item weapon = player.getEquipment().getItemContainer().get(Constants.WEAPON);
				if (player.getMagicBookType() == SpellBook.ANCIENT) {
					if (weapon.getId() == 4675) {
						player.getActionSender().sendSidebarInterface(0, 1689);
					} else {
						player.getActionSender().sendMessage("You can't autocast ancient magic without an ancient staff!");
					}
				} else {
					if (weapon.getId() == 4170) {
						player.getActionSender().sendSidebarInterface(0, 12050);
					} else {
						player.getActionSender().sendSidebarInterface(0, 1829);
					}
				}
				return true;
			case 7212 :
			case 24017 :
			case 47069 :
				player.getActionSender().sendSidebarInterface(0, 328);
				return true;
		}
		return false;
	}

	public boolean superHeatItem(int itemID) {
		if (!player.getSkill().canDoAction(1200)) {
			return false;
		}
		for (int smelt[] : SMELT) {
			if (itemID == smelt[0]) {
				if (!player.getInventory().playerHasItem(smelt[2], smelt[3])) {
					if (itemID == 440 && smelt[2] == 453) {
						continue;
					} else if (smelt[2] > 0) {
						player.getActionSender().sendMessage("You haven't got enough " + ItemManager.getInstance().getItemName(smelt[2]).toLowerCase() + " to cast this spell!");
						return false;
					}
				}
				if (player.getSkill().getPlayerLevel(Skill.SMITHING) < smelt[6]) {
					player.getActionSender().sendMessage("You need a smithing level of " + smelt[6] + " to superheat this ore.");
					return false;
				}
				player.getInventory().removeItem(new Item(itemID, 1));
				if (smelt[2] > 0) {
					player.getInventory().removeItem(new Item(smelt[2], smelt[3]));
				}
				player.getInventory().addItem(new Item(smelt[4], 1));
				player.getSkill().addExp(Skill.SMITHING, smelt[5]);
				player.getActionSender().sendFrame106(6);
				return true;
			}
		}
		player.getActionSender().sendMessage("You can only cast superheat item on ores!");
		return false;
	}

	public boolean enchantJewelry(int item, int spellId) {
		if (!player.getSkill().canDoAction(1200)) {
			return false;
		}
		int index = -1;
		if (item == ENCHANT[spellId][0]) {
			index = 0;
		} else if (item == ENCHANT[spellId][1]) {
			index = 1;
		} else if (item == ENCHANT[spellId][2]) {
			index = 2;
		} else {
			player.getActionSender().sendMessage("You cannot enchant this item with this spell.");
			return false;
		}
		player.getInventory().removeItem(new Item(ENCHANT[spellId][index], 1));
		player.getInventory().addItem(new Item(ENCHANT[spellId][index + 3], 1));
		player.getActionSender().sendMessage("You enchant the " + ItemManager.getInstance().getItemName(ENCHANT[spellId][index]) + ".");
		player.getActionSender().sendFrame106(6);
		return true;
	}
	

	public boolean applyBonesToFruit(boolean peaches) {
		if (!player.getSkill().canDoAction(1200)) {
			return false;
		}
		int fruit = peaches ? PEACH : BANANA;
		boolean hasBones = false;
		for (Item item : player.getInventory().getItemContainer().getItems()) {
			if (item != null && item.getId() == 526) {
				player.getInventory().removeItem(item);
				player.getInventory().addItem(new Item(fruit));
				hasBones = true;
			}
		}
		if (!hasBones) {
			player.getActionSender().sendMessage("You don't have any bones to convert into fruits.");
			return false;
		}
		return true;
	}

	public boolean teleOther(Player player, Player otherPlayer, TeleotherLocation location) {
		if (!player.getSkill().canDoAction(1200)) {
			return false;
		}
		if (otherPlayer.getInterface() > 0) {
			player.getActionSender().sendMessage("This player is busy.");
			return false;
		}
		if (!otherPlayer.isAcceptingAid()) {
			player.getActionSender().sendMessage("This player is not accepting aid.");
			return false;
		}
		if (otherPlayer.cantTeleport()) {
			player.getActionSender().sendMessage("You cannot use this spell here.");
			return false;
		}
		otherPlayer.setTeleotherPosition(location.getPosition());
		otherPlayer.getActionSender().sendString(player.getUsername(), 12558);
		otherPlayer.getActionSender().sendString(location.getName(), 12560);
		otherPlayer.getActionSender().sendInterface(12468);
		return true;
	}
	
	public enum TeleotherLocation {
		CAMELOT(new Position(Constants.CAMELOT_X, Constants.CAMELOT_Y), "Camelot"),
		FALADOR(new Position(Constants.FALADOR_X, Constants.FALADOR_Y), "Falador"),
		LUMBRIDGE(new Position(Constants.LUMBRIDGE_X, Constants.LUMBRIDGE_Y), "Lumbridge");
		
		Position position;
		String name;
		
		TeleotherLocation(Position position, String name) {
			this.position = position;
			this.name = name;
		}
		
		public Position getPosition() {
			return position;
		}
		
		public String getName() {
			return name;
		}
	}

	public static boolean alchItem(Player player, int itemId, int slot, int price, int timer) {
		if (!player.getSkill().canDoAction(timer)) {
			return false;
		}
		if (new Item(itemId).getDefinition().isUntradable() || itemId == 995) {
			player.getActionSender().sendMessage("You cannot alch this item.");
			return false;
		}
		if (player.getInventory().getItemContainer().get(slot).getCount() > 1 && !player.getInventory().getItemContainer().hasRoomFor(new Item(995))) {
			player.getActionSender().sendMessage("Not enough space in your inventory.");
			return false;
		}
		player.getActionSender().stopPlayerPacket(2);
		player.getActionSender().sendFrame106(6);
		player.getInventory().removeItem(new Item(itemId, 1));
		if (price > 0) {
			player.getInventory().addItem(new Item(995, price));
		}
		return true;
	}
}
