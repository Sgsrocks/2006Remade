package com.rs2.model;

import java.awt.Rectangle;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

import com.rs2.model.content.Following;
import com.rs2.model.content.combat.AttackScript;
import com.rs2.model.content.combat.AttackType;
import com.rs2.model.content.combat.AttackUsableResponse;
import com.rs2.model.content.combat.CombatScript;
import com.rs2.model.content.combat.DistanceCheck;
import com.rs2.model.content.combat.InCombatTick;
import com.rs2.model.content.combat.effect.Effect;
import com.rs2.model.content.combat.effect.EffectTick;
import com.rs2.model.content.combat.hit.Hit;
import com.rs2.model.content.combat.hit.HitDef;
import com.rs2.model.content.combat.hit.HitRecord;
import com.rs2.model.content.combat.hit.HitType;
import com.rs2.model.content.skills.prayer.Prayer;
import com.rs2.model.npcs.Npc;
import com.rs2.model.players.Player;
import com.rs2.model.tick.CycleEvent;
import com.rs2.model.tick.TickTimer;
import com.rs2.pf.Path;
import com.rs2.pf.PathFinder;
import com.rs2.pf.StraightPathFinder;
import com.rs2.util.clip.Region;

public abstract class Entity {

	private int index = -1, faceIndex;

	private Entity interactingEntity;
	private Entity tradingEntity;
	private Entity combatingEntity;
	private Entity followingEntity;
	private Entity target;

    private Position deathPosition;

    private int uniqueId;

	private boolean isDead;
	private boolean instigatingAttack;
	private boolean dontWalk = false;
	private boolean dontFollow = false;
	private boolean dontAttack = false;

	private int transformOnAggression;
	private int hitType;
	private int followDistance = 1;
	private int skillTask;
	private int oldSkillTask;
	private int currentSkillTask;

	private double poisonDamage;

	private Rectangle area;
	private Rectangle combatArea;

	private InCombatTick inCombatTick = new InCombatTick(this, 0);
	private InCombatTick pjTimer = new InCombatTick(this, 0);
	private TickTimer combatDelayTick = new TickTimer(0);
	private CombatScript combatScript = CombatScript.DEFAULT_COMBAT_SCRIPT;
	private CycleEvent skilling;

    private Following following = new Following(this);

    private TickTimer movementPaused = new TickTimer(0);

    private TickTimer teleblockTimer = new TickTimer(0);
	private TickTimer frozenTimer = new TickTimer(0);
    private TickTimer stunTimer = new TickTimer(0);
    private TickTimer poisonImmunityTimer = new TickTimer(0);
    private TickTimer freezeImmunityTimer = new TickTimer(0);
    private TickTimer fireImmunityTimer = new TickTimer(0);

	@SuppressWarnings("rawtypes")
	private List<EffectTick> effects = new LinkedList<EffectTick>();

	private TickTimer godChargeDelay = new TickTimer(0), godChargeEffect = new TickTimer(0);
    private MovementHandler movementHandler = new MovementHandler(this);

	private Queue<HitRecord> hitRecordQueue = new PriorityQueue<HitRecord>(1, new Comparator<HitRecord>() {
		@Override
		public int compare(HitRecord a, HitRecord b) {
			return b.getDamage() - a.getDamage();
		}
	});

	private int size;
    private int primaryDirection = -1;
    private int secondaryDirection = -1;

	private Map<String, Object> attributes = new HashMap<String, Object>();

	private Position position;
	private UpdateFlags updateFlags = new UpdateFlags(this);

	public abstract void reset();
	public abstract void initAttributes();
	public abstract void process();
	public abstract void dropItems(Entity killer);
	public abstract void heal(int amount);

	public void hit(int damage, HitType hitType) {
		// setting start delay to -1 makes it hit asap, no delay
		HitDef hitDef = new HitDef(null, hitType, damage).setStartingHitDelay(-1).setUnblockable(true).setDoBlock(false);
		Hit hit = new Hit(null, this, hitDef);
		hit.initialize();
	}

	public void expireHitRecords() {
		for (Iterator<HitRecord> hitRecordIterator = hitRecordQueue.iterator(); hitRecordIterator.hasNext();) {
			if (hitRecordIterator.next().expired())
				hitRecordIterator.remove();
		}
	}

    public void setPrimaryDirection(int primaryDirection) {
        this.primaryDirection = primaryDirection;
    }

    public int getPrimaryDirection() {
        return primaryDirection;
    }

    public void setSecondaryDirection(int secondaryDirection) {
        this.secondaryDirection = secondaryDirection;
    }

    public int getSecondaryDirection() {
        return secondaryDirection;
    }

	public boolean isMoving() {
        Queue<MovementHandler.Point> waypoints = getMovementHandler().getWaypoints();
        return !waypoints.isEmpty() && waypoints.peek().getDirection() != -1;
	}

	public boolean isRunning() {
        return isMoving() && (getMovementHandler().isRunToggled() || getMovementHandler().isRunPath());
	}

	public int getBonus(int bonusId) {
		if (isPlayer()) {
			Player player = (Player) this;
			return player.getBonuses().get(bonusId);
		} else {
			Npc npc = (Npc) this;
			return npc.getCombatDef().getBonuses().get(bonusId);
		}
	}

	/**
	 * Only use through the attacker (attacker.applyPrayerEffects()).
	 */
	public void applyPrayerEffects(Entity victim, int hit) {
		if (victim.isPlayer()) {
			Player player = (Player) this;
			Player otherPlayer = (Player) victim;
			player.getPrayer().applySmiteEffect(otherPlayer, hit);
		}
	}

	public int applyPrayerToHit(Entity victim, int hit) {
		if (victim.isPlayer()) {
			return Prayer.prayerHitModifiers(this, victim, hit);
		}
		return hit;
	}

	public boolean Area(int x, int x1, int y, int y1) {
		return getPosition().getX() >= x && getPosition().getX() <= x1 && getPosition().getY() >= y && getPosition().getY() <= y1;
	}

	public boolean Area(int x, int x1, int y, int y1, int x2, int y2) {
		return x2 >= x && x2 <= x1 && y2 >= y && y2 <= y1;
	}

	public boolean inCw() {
		return inCwGame() || inSaraWait() || inZammyWait() || Area(2432, 2446, 3082, 3117);
	}

	public boolean inCwSafe() {
		return (Area(2423, 2431, 3072, 3080) || Area(2368, 2376, 3127, 3135)) && getPosition().getZ() == 1;
	}

	public boolean inZammyWait() {
		return Area(2409, 2431, 9511, 9535);
	}

	public boolean inSaraWait() {
		return Area(2368, 2392, 9479, 9498);
	}

	public boolean inCwGame() {
		return Area(2368, 2431, 9479, 9535) || Area(2368, 2431, 3072, 3135) && !inSaraWait() && !inZammyWait();
	}

	public boolean inCwUnderground() {
		return Area(2368, 2431, 9479, 9535) && !inSaraWait() && !inZammyWait();
	}

	public boolean inZammyBase() {
		return Area(2368, 2384, 3118, 3135);
	}

	public boolean inSaraBase() {
		return Area(2414, 2431, 3072, 3088);
	}

	public boolean inPits() {
		return Area(2370, 2430, 5122, 5168);
	}

	public boolean inPitsWait() {
		return Area(2394, 2404, 5169, 5175);
	}

	public boolean inCaves() {
		return Area(2371, 2422, 5062, 5117);
	}

	public boolean inMageArena() {
		return Area(3078, 3133, 3908, 3959);
	}

	public boolean inWild() {
		return Area(2942, 3391, 3520, 3965) || Area(2942, 3391, 9919, 10365);
	}

	public boolean nextToWildy(int x, int y) {
		return Area(2942, 3391, 3517, 3965, x, y) || Area(2942, 3391, 9917, 10365, x, y);
	}

	public boolean inHomeArea() {
		return Area(2881, 2930, 3522, 3582);
	}

	public int getWildernessLevel() {
		if (!inWild()) {
			return 0;
		} else {
			int modY = getPosition().getY() > 6400 ? getPosition().getY() - 6400 : getPosition().getY();
			return (modY - 3520) / 8 + 1;
		}
	}

	/*public boolean inMulti() {
		return Areas.inMultiArea(getPosition()) || inCwGame() || inPits() || inCaves();
	}*/

	public boolean inWaterbirthIsland() {
		return Area(2494, 2569, 3701, 3785);
	}

	public boolean inMulti() {
		return Area(3281, 3304, 3158, 3178) || Area(2660, 2675, 3226, 3249) || Area(1758, 1864, 4347, 4418) || Area(3067, 3099, 3402, 3449) || Area(2655, 2695, 3705, 3740) || Area(3136, 3327, 3519, 3607) || Area(3190, 3327, 3648, 3839) || Area(3200, 3390, 3840, 3967) || Area(2992, 3007, 3912, 3967) || Area(2946, 2959, 3816, 3831) || Area(3008, 3199, 3856, 3903) || Area(3008, 3071, 3600, 3711) || Area(3072, 3327, 3608, 3647) || Area(2624, 2690, 2550, 2619) || Area(2896, 2927, 3595, 3630) || Area(2894, 2938, 4413, 4478) || Area(2256, 2287, 4680, 4711) || Area(2863, 2878, 5350, 5372) || Area(2396, 2435, 4691, 4739) || Area(2365, 2395, 4702, 4739) || Area(3461, 3514, 9473, 9525) || Area(2448, 2481, 4764, 4795) || Area(2703, 2734, 9797, 9832) || Area(1868, 1950, 4343, 4415) || Area(2429, 2566, 10112, 10180) || inCwGame() || inPits() || inCaves();
	}

	public boolean rangableArea(int x, int y) {
		return Area(2414, 2416, 3074, 3085, x, y) || Area(2418, 2424, 3087, 3089, x, y) || Area(2412, 2417, 3086, 3091, x, y) || Area(2383, 2385, 3122, 3133, x, y) || Area(2375, 2381, 3118, 3120, x, y) || Area(2382, 2387, 3116, 3121, x, y);
	}

	public boolean nonRangableArea(int x, int y) {
		return Area(2382, 2385, 3134, 3135, x, y) || Area(2368, 2379, 3124, 3135, x, y) || Area(2414, 2417, 3072, 3074, x, y) || Area(2420, 2431, 3072, 3083, x, y);
	}

	public boolean inRandomEvent() {
		return Area(2587, 2619, 4760, 4785);
	}

	public boolean inBank() {
		return Area(3090, 3099, 3487, 3500) || Area(3089, 3090, 3492, 3498) || Area(3248, 3258, 3413, 3428) || Area(3179, 3191, 3432, 3448) || Area(2944, 2948, 3365, 3374) || Area(2942, 2948, 3367, 3374) || Area(2944, 2950, 3365, 3370) || Area(3008, 3019, 3352, 3359) || Area(3017, 3022, 3352, 3357) || Area(3203, 3213, 3200, 3237) || Area(3212, 3215, 3200, 3235) || Area(3215, 3220, 3202, 3235) || Area(3220, 3227, 3202, 3229) || Area(3227, 3230, 3208, 3226) || Area(3226, 3228, 3230, 3211) || Area(3227, 3229, 3208, 3226);
	}

	public boolean inBarrows() {
		return Area(3520, 9660, 3585, 9730);
	}

	public boolean inDuelArena() {
		return Area(3333, 3357, 3244, 3258) || Area(3333, 3357, 3225, 3239) || Area(3333, 3357, 3206, 3220) || Area(3364, 3388, 3244, 3258) || Area(3364, 3388, 3225, 3239) || Area(3364, 3388, 3206, 3220);
	}

	public boolean isInDuelArea() {
		/*for(MinigameAreas.Area area : DuelAreas.NORMAL_AREA)
        if(MinigameAreas.isInArea(player.getPosition(), area.enlarge(2)))
            return true;
	for(MinigameAreas.Area area : DuelAreas.OBSTACLE_AREA)
        if(MinigameAreas.isInArea(player.getPosition(), area.enlarge(2)))
            return true;
	return false;*/
		return (Area(3325, 3410, 3200, 3266) || Area(3341, 3410, 3267, 3288) || Area(3312, 3322, 3224, 3247)) && !inDuelArena();
	}

	public void setIndex(int index) {
		this.index = index;
	}

	public int getIndex() {
		return index;
	}

	public void setFaceIndex(int faceIndex) {
		this.faceIndex = faceIndex;
	}

	public int getFaceIndex() {
		return faceIndex;
	}

	public boolean isPlayer() {
		if (this == null) {
			return false;
		}
		return faceIndex >= 32768;
	}

	public boolean isNpc() {
		if (this == null) {
			return false;
		}
		return faceIndex < 32768;
	}

	public void setInteractingEntity(Entity interactingEntity) {
        if (isNpc() && interactingEntity != null)
            getUpdateFlags().setEntityFaceIndex(interactingEntity.getFaceIndex());
		this.interactingEntity = interactingEntity;
	}

	public Entity getInteractingEntity() {
		return interactingEntity;
	}

	public void setPosition(Position position) {
        this.position = position;
    }

	public Position getPosition() {
		return position;
	}

	public void setUpdateFlags(UpdateFlags updateFlags) {
		this.updateFlags = updateFlags;
	}

	public UpdateFlags getUpdateFlags() {
		return updateFlags;
	}

	public void setDead(boolean isDead) {
		this.isDead = isDead;
	}

	public boolean isDead() {
		return isDead;
	}

	public void setAttributes(Map<String, Object> attributes) {
		this.attributes = attributes;
	}

	public Map<String, Object> getAttributes() {
		return attributes;
	}

	public void setHitType(int hitType) {
		this.hitType = hitType;
	}

	public int getHitType() {
		return hitType;
	}

	public boolean isInstigatingAttack() {
		return instigatingAttack;
	}

	public void setInstigatingAttack(boolean instigatingAttack) {
		this.instigatingAttack = instigatingAttack;
	}

	public void setCombatingEntity(Entity combatingEntity) {
		this.combatingEntity = combatingEntity;
	}

	public Entity getCombatingEntity() {
		return combatingEntity;
	}

	public void setTarget(Entity target) {
		this.target = target;
	}

	public Entity getTarget() {
		return target;
	}

	public void setFollowingEntity(Entity followingEntity) {
		this.followingEntity = followingEntity;
	}

	public Entity getFollowingEntity() {
		return followingEntity;
	}

	public void setSize(int size) {
		this.size = size;
	}

	public int getSize() {
		return size;
	}

	public int getSizeMinusOne() {
		return size - 1;
	}

	public void setFollowDistance(int followDistance) {
		this.followDistance = followDistance;
	}
	public int getFollowDistance() {
		return followDistance;
	}

	public abstract int getCurrentHp();

	public abstract void setCurrentHp(int hp);

	public abstract int getMaxHp();

	public abstract int getDeathAnimation();

	public abstract int getBlockAnimation();

	public abstract int getDeathAnimationLength();

	public abstract int getBaseAttackLevel(AttackType attackType);

	public abstract int getBaseDefenceLevel(AttackType attackType);

	public abstract boolean isProtectingFromCombat(AttackType attackType, Entity attacker);
    
    public abstract void teleport(Position position);

    public TickTimer getMovementPaused() {
        return movementPaused;
    }

	public Entity findKiller() {
		HitRecord mostDamage = hitRecordQueue.peek();
		if (mostDamage != null) {
			return mostDamage.getEntity();
		}
		return null;
	}

	public InCombatTick getInCombatTick() {
		return inCombatTick;
	}

	public InCombatTick getPjTimer() {
		return pjTimer;
	}

	public TickTimer getCombatDelayTick() {
		return combatDelayTick;
	}

	public void setCombatDelay(int delay) {
		combatDelayTick.setWaitDuration(delay);
		combatDelayTick.reset();
	}

	public Queue<HitRecord> getHitRecordQueue() {
		return hitRecordQueue;
	}

	public HitRecord getHitRecord(Entity attacker) {
		for (HitRecord hitRecord : hitRecordQueue) {
			if (hitRecord.getEntity() == attacker)
				return hitRecord;
		}
		return null;
	}

	public int fillUsableAttacks(List<AttackUsableResponse> usableAttackScripts, Entity victim, int distance) {
		// here we generate all the attacks, then filter them by distance
		AttackScript[] possibleAttackScripts = combatScript.generateAttacks(this, victim, distance);
		int possibleAttacks = possibleAttackScripts.length;
		for (AttackScript attackScript : possibleAttackScripts) {
			attackScript.initialize();
			AttackUsableResponse.Type type = attackScript.isUsable();

			if (type == AttackUsableResponse.Type.FAIL) {
				possibleAttacks--;
				if (isPlayer() && ((Player) this).isSpecialAttackActive()) {
					((Player) this).setSpecialAttackActive(false);
					((Player) this).updateSpecialBar();
				}
				continue;
			}
			int distanceRequired = attackScript.distanceRequired() + DistanceCheck.extraDistance(this, victim);

			if (!Following.withinRange(this, victim, distanceRequired))
				type = AttackUsableResponse.Type.WAIT;

            usableAttackScripts.add(new AttackUsableResponse(attackScript, type));
		}
		return possibleAttacks;
	}

	/*public void stopSkilling() {
		if (skilling != null) {
			skilling.stop();
			skilling = null;
		}
	}  */

	public void setSkilling(CycleEvent event) {
		this.skilling = event;
	}

	public CycleEvent getSkilling() {
		return skilling;
	}

	public void setSkillTask(int skillTask) {
		this.skillTask = skillTask;
	}

	public int getTask() {
		skillTask++;
		if (skillTask > Integer.MAX_VALUE - 2) {
			skillTask = 0;
		}
		return skillTask;
	}

	public boolean checkTask(int task) {
		return task == skillTask;
	}

	@SuppressWarnings("rawtypes")
	public boolean canAddEffect(Effect effect) {
		return !hasEffect(effect) && effect.isCompatible(this);
	}

	public void removeAllEffects() {
		removeAllEffects(null);
	}

	@SuppressWarnings("rawtypes")
	public void removeAllEffects(Class<? extends Effect> effectClass) {
		List<EffectTick> remove = new LinkedList<EffectTick>();
		remove.addAll(effects);
		for (EffectTick tick : remove) {
			if (effectClass == null || tick.getEffect().getClass() == effectClass) {
				tick.stop();
			}
		}
		remove.clear();
	}

	@SuppressWarnings("rawtypes")
	public boolean hasEffect(Class<? extends Effect> clazz) {
		for (EffectTick tick : effects)
			if (tick.getEffect().getClass() == clazz)
				return true;
		return false;
	}

	@SuppressWarnings("rawtypes")
	public boolean hasEffect(Effect effect) {
		for (EffectTick tick : effects)
			if (tick.getEffect().equals(effect))
				return true;
		return false;
	}

    
    @SuppressWarnings("rawtypes")
	public void giveEffect(Effect effect, Entity attacker) {
        HitDef hitDef = new HitDef(null, null, -1);
        hitDef.addEffect(effect);
        Hit hit = new Hit(attacker, this, hitDef);
        hit.initialize();
    }

    /**
     * Do not use this
     * @param effect the effect
     */
	@SuppressWarnings("rawtypes")
	public void addEffect(EffectTick effect) {
		effects.add(effect);
	}
    /**
     * Do not use this
     * @param effect the effect
     */
	@SuppressWarnings("rawtypes")
	public void removeEffect(Effect effect) {
		EffectTick remove = null;
		for (EffectTick tick : effects) {
			if (tick.getEffect() == effect) {
				remove = tick;
			}
		}
		if (remove != null)
			effects.remove(remove);
	}

	public boolean hasGodChargeEffect() {
		return !godChargeEffect.completed();
	}

	public void refreshGodChargeEffect() {
		godChargeEffect.setWaitDuration(700);
		godChargeEffect.reset();
		godChargeDelay.setWaitDuration(100);
		godChargeDelay.reset();
	}

	public TickTimer getGodChargeDelayTimer() {
		return godChargeDelay;
	}

	public boolean isAttacking() {
		return getCombatingEntity() != null;
	}

    /*
      * This stuff isn't needed, just for old combat errors
      */
	public enum AttackTypes {
		MELEE, RANGED, MAGIC
	}

	private AttackTypes attackType = AttackTypes.MELEE;

	public void setAttackType(AttackTypes attackType) {
		this.attackType = attackType;
	}
	public AttackTypes getAttackType() {
		return attackType;
	}

    public void setCombatScript(CombatScript combatScript) {
        this.combatScript = combatScript;
    }

    /**
     * Sets the MovementHandler.
     *
     * @param movementHandler
     *            the movement handler
     */
    public void setMovementHandler(MovementHandler movementHandler) {
        this.movementHandler = movementHandler;
    }

    /**
     * Gets the MovementHandler.
     *
     * @return the movement handler
     */
    public MovementHandler getMovementHandler() {
        return movementHandler;
    }
    
    public void walkTo(int x, int y, boolean clipped) {
        walkTo(new Position(x, y, getPosition().getZ()), clipped);
    }

    public void walkTo(Position to, boolean clipped) {
        PathFinder pf = new StraightPathFinder();
        Path p = pf.findPath(this, to, clipped);
        getMovementHandler().reset();
        while (!p.getPoints().isEmpty()) {
            com.rs2.pf.Point point = p.getPoints().poll();
            getMovementHandler().addToPath(new Position(point.getX(), point.getY(), getPosition().getZ()));
        }
        getMovementHandler().finish();
    }

    public Following getFollowing() {
        return following;
    }
   
	/**
	 * @param combatArea the combatArea to set
	 */
	public void setCombatArea(Rectangle combatArea) {
		this.combatArea = combatArea;
	}
	/**
	 * @return the combatArea
	 */
	public Rectangle getCombatArea() {
		return combatArea;
	}
	
	/**
	 * @param area the area to set
	 */
	public void setArea(Rectangle area) {
		this.area = area;
	}

	/**
	 * @return the area
	 */
	public Rectangle getArea() {
		return area;
	}

    public void setCombatArea(int distance) {
        //setCombatArea(new Rectangle(getPosition().getX() - distance, getPosition().getY() + distance + getSizeMinusOne(), getSize() + distance + 1, getSize() + distance + 1));
    }

	public boolean goodDistanceEntity(Entity other, int distance) {
        Rectangle thisArea = new Rectangle(getPosition().getX()-distance, getPosition().getY()-distance, 2*distance+getSize(), 2*distance+getSize());
        Rectangle otherArea = new Rectangle(other.getPosition().getX(), other.getPosition().getY(), other.getSize(), other.getSize());
        return thisArea.intersects(otherArea);
	}
	 public boolean inEntity(Entity other) {
	        Rectangle thisArea = new Rectangle(getPosition().getX(), getPosition().getY(), getSize(), getSize());
	        Rectangle otherArea = new Rectangle(other.getPosition().getX(), other.getPosition().getY(), other.getSize(), other.getSize());
	        return thisArea.intersects(otherArea);
	 }

	/*public boolean goodDistanceEntity(Entity other, int distance) {
		Rectangle thisArea = new Rectangle(getPosition().getX()-distance, getPosition().getY()-distance, 2*distance+getSize(), 2*distance+getSize());
        //Rectangle thisArea = new Rectangle(getPosition().getX()-distance-getSizeMinusOne(), getPosition().getY()-distance-getSizeMinusOne(), (2*distance+2*getSizeMinusOne()) + 1, (2*distance+2*getSizeMinusOne()) + 1);
        return thisArea.contains(other.getArea());
	}*/

	/*public boolean inEntity(Entity other) {
        return getArea().intersects(other.getArea());
	}*/

	public void setNewSkillTask() {
		currentSkillTask++;
		oldSkillTask = currentSkillTask;
		if (oldSkillTask > Integer.MAX_VALUE - 2 || currentSkillTask > Integer.MAX_VALUE - 2) {
			oldSkillTask = 0;
			currentSkillTask = 0;
		}
	}

	public void setCurrentSkillTask() {
		currentSkillTask++;
	}

	public boolean checkNewSkillTask() {
		return oldSkillTask == currentSkillTask;
	}

    public int getUniqueId() {
        return uniqueId;
    }

    public void setUniqueId(int uniqueId) {
        this.uniqueId = uniqueId;
    }

    public Position getDeathPosition() {
        return deathPosition;
    }

    public void setDeathPosition(Position position) {
        this.deathPosition = position;
    }

	/**
	 * @param tradingEntity the tradingEntity to set
	 */
	public void setTradingEntity(Entity tradingEntity) {
		this.tradingEntity = tradingEntity;
	}
	/**
	 * @return the tradingEntity
	 */
	public Entity getTradingEntity() {
		return tradingEntity;
	}

	public boolean canMove(int startX, int startY, int endX, int endY, int height, int xLength, int yLength) {
		return Region.canMove(startX, startY, endX, endY, height, xLength, yLength);
	}

	public boolean canMove(int x, int y) {
		return Region.canMove(getPosition().getX(), getPosition().getY(), getPosition().getX() + x, getPosition().getY() + y, getPosition().getZ(), getSize(), getSize());
	}

    public TickTimer getFrozenImmunity() {
		return freezeImmunityTimer;
	}

    public TickTimer getPoisonImmunity() {
		return poisonImmunityTimer;
	}

    public TickTimer getFireImmunity() {
		return fireImmunityTimer;
	}

    public TickTimer getTeleblockTimer() {
		return teleblockTimer;
	}

    public TickTimer getFrozenTimer() {
		return frozenTimer;
	}

    public TickTimer getStunTimer() {
		return stunTimer;
	}

	public boolean isFrozenImmune() {
		return !freezeImmunityTimer.completed();
	}

	public boolean isFireImmune() {
		return !fireImmunityTimer.completed();
	}

	public boolean isPoisonImmune() {
		return !poisonImmunityTimer.completed();
	}

	public boolean isTeleblocked() {
		return !teleblockTimer.completed();
	}

	public boolean cantTeleport() {
		return inRandomEvent() || inPits() || inPitsWait() || inCaves() || inDuelArena();
	}

	public boolean isFrozen() {
		return !frozenTimer.completed();
	}

	public boolean isStunned() {
		return !stunTimer.completed();
	}

	public void resetEffectTimers() {
		teleblockTimer.setWaitDuration(0);
		frozenTimer.setWaitDuration(0);
		stunTimer.setWaitDuration(0);
		teleblockTimer.reset();
		frozenTimer.reset();
		stunTimer.reset();
	}

	public void resetImmuneTimers() {
		poisonImmunityTimer.setWaitDuration(0);
		freezeImmunityTimer.setWaitDuration(0);
		fireImmunityTimer.setWaitDuration(0);
		poisonImmunityTimer.reset();
		freezeImmunityTimer.reset();
		fireImmunityTimer.reset();
	}
	/**
	 * @param poison the poisonDamage to set
	 */
	public void setPoisonDamage(double poison) {
		this.poisonDamage = poison;
	}
	/**
	 * @return the poisonDamage
	 */
	public double getPoisonDamage() {
		return poisonDamage;
	}
	/**
	 * @param transformOnAggression the transformOnAttack to set
	 */
	public void setTransformOnAggression(int transformOnAggression) {
		this.transformOnAggression = transformOnAggression;
	}
	/**
	 * @return the transformOnAttack
	 */
	public int isTransformOnAggression() {
		return transformOnAggression;
	}
	/**
	 * @param dontWalk the dontWalk to set
	 */
	public void setDontWalk(boolean dontWalk) {
		this.dontWalk = dontWalk;
	}
	/**
	 * @return the dontWalk
	 */
	public boolean isDontWalk() {
		return dontWalk;
	}
	/**
	 * @param dontFollow the dontFollow to set
	 */
	public void setDontFollow(boolean dontFollow) {
		this.dontFollow = dontFollow;
	}
	/**
	 * @return the dontFollow
	 */
	public boolean isDontFollow() {
		return dontFollow;
	}
	
	public boolean isDoorSupport() {
		return isNpc() && (((Npc) this).getNpcId() == 2440 || ((Npc) this).getNpcId() == 2443 || ((Npc) this).getNpcId() == 2446);
	}
	/**
	 * @param dontAttack the dontAttack to set
	 */
	public void setDontAttack(boolean dontAttack) {
		this.dontAttack = dontAttack;
	}
	/**
	 * @return the dontAttack
	 */
	public boolean isDontAttack() {
		return dontAttack;
	}
}
