package battlecode.client.viewer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import battlecode.client.viewer.render.RenderConfiguration;
import battlecode.common.Direction;
import battlecode.common.MapLocation;
import battlecode.common.RobotInfo;
import battlecode.common.RobotType;
import battlecode.common.Team;
import battlecode.serial.RoundStats;
import battlecode.world.GameMap;
import battlecode.world.signal.AttackSignal;
import battlecode.world.signal.BashSignal;
import battlecode.world.signal.BroadcastSignal;
import battlecode.world.signal.BytecodesUsedSignal;
import battlecode.world.signal.CastSignal;
import battlecode.world.signal.ControlBitsSignal;
import battlecode.world.signal.DeathSignal;
import battlecode.world.signal.HealthChangeSignal;
import battlecode.world.signal.IndicatorDotSignal;
import battlecode.world.signal.IndicatorLineSignal;
import battlecode.world.signal.IndicatorStringSignal;
import battlecode.world.signal.LocationOreChangeSignal;
import battlecode.world.signal.MineSignal;
import battlecode.world.signal.MissileCountSignal;
import battlecode.world.signal.MovementOverrideSignal;
import battlecode.world.signal.MovementSignal;
import battlecode.world.signal.RobotInfoSignal;
import battlecode.world.signal.SelfDestructSignal;
import battlecode.world.signal.SpawnSignal;
import battlecode.world.signal.TeamOreSignal;
import battlecode.world.signal.TransferSupplySignal;
import battlecode.world.signal.XPSignal;

public abstract class AbstractDrawState<DrawObject extends AbstractDrawObject> extends GameState {

  protected abstract DrawObject createDrawObject(RobotType type, Team team, int id);

  protected abstract DrawObject createDrawObject(DrawObject o);
  protected Map<Integer, DrawObject> groundUnits;
  protected Map<Integer, DrawObject> airUnits;
  protected Map<Integer, FluxDepositState> fluxDeposits;
  protected Set<MapLocation> encampments;
    protected DrawableMapMemory mapMemoryImage;
  protected double[] teamHP = new double[2];
    protected Map<Team, DrawObject> hqs;
    protected Map<Team, Double> teamSupplyLevels;
    protected Map<Team, Map<Integer, DrawObject>> towers
    = new EnumMap<Team, Map<Integer, DrawObject>>(Team.class); // includes dead towers
  protected int [] coreIDs = new int [2];
  protected Map<MapLocation,Team> mineLocs = new HashMap<MapLocation, Team>();
  protected Map<MapLocation, Double> locationOre = new HashMap<MapLocation, Double>();
  protected static MapLocation origin = null;
  protected GameMap gameMap;
  protected int currentRound;
  protected RoundStats stats = null;
  protected double[] teamResources = new double[2];
  protected double[][] researchProgress = new double[2][4];
  protected List<IndicatorDotSignal> newIndicatorDots = new ArrayList<IndicatorDotSignal>();
  protected List<IndicatorLineSignal> newIndicatorLines = new ArrayList<IndicatorLineSignal>();
  protected IndicatorDotSignal [] indicatorDots = new IndicatorDotSignal [0];
  protected IndicatorLineSignal [] indicatorLines = new IndicatorLineSignal [0];
    protected Map<Team, Map<RobotType, Integer>> totalRobotTypeCount = new EnumMap<Team, Map<RobotType, Integer>>(Team.class); // includes inactive buildings

  // Fog of war!
  // This uses a BufferedImage as storage for data about visibility - that way, it can just be drawn!
  // That might cause problems if you, say, enable antialisaing, though.
  // (This is both graphics and memory, but put it here because it cares about signals.)
  protected final static class DrawableMapMemory {
	  private final int[] buffer;
	  public final MapLocation origin;
	  public final int width, height;
	  
	  private static final int NOT_SEEN = Color.BLACK.getRGB();
	  private static final int SEEN_A   = new Color(255,0,0,20).getRGB();
	  private static final int SEEN_B   = new Color(0,0,255,20).getRGB();
	  private static final int SEEN_BOTH = new Color(0,0,0,0).getRGB();
	  
	  public DrawableMapMemory(final MapLocation origin, final int width, final int height) {
		  this.width = width;
		  this.height = height;
		  this.origin = origin;
		  buffer = new int[width*height];
		  
		  for (int i = 0; i < width*height; i++) {
			  buffer[i] = NOT_SEEN;
		  }
	  }
	  
	  public DrawableMapMemory(final DrawableMapMemory source) {
		  this.width = source.width;
		  this.height = source.height;
		  this.origin = source.origin;
		  this.buffer = new int[width*height];
		  
		  for (int i = 0; i < width*height; i++) {
			  buffer[i] = source.buffer[i];
		  }
	  }
	  
	  public boolean compatible(final BufferedImage targetImage) {
		  return targetImage != null
				  && targetImage.getWidth() == this.width
				  && targetImage.getHeight() == this.height
				  && targetImage.getType() == BufferedImage.TYPE_INT_ARGB;
	  }
	  
	  public BufferedImage createCompatibleBufferedImage() {
		  return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); // Same color type as the array
	  }
	  
	  public void copyMemoryToImage(final BufferedImage image) {
		  image.setRGB(
				  0, 0, 					// Start at origin
				  this.width, this.height, 	// Cover whole image
				  this.buffer, 			 	// The data we're blitting
				  0,						// No offset in array
				  this.width				// Scansize (this seems redundant...)
				  );
	  }
	  
	  public void rememberLocation(final Team team, final MapLocation loc, final int rsq) {
		  final int radius = (int) Math.sqrt(rsq);
		  final int minXPos = loc.x - radius;
		  final int maxXPos = loc.x + radius;
		  final int minYPos = loc.y - radius;
		  final int maxYPos = loc.y + radius;

		  for (int x = minXPos; x <= maxXPos; x++) {
			  for (int y = minYPos; y <= maxYPos; y++) {
				  final int dx = x - loc.x;
				  final int dy = y - loc.y;
				  if (dx*dx + dy*dy <= rsq) {
					  // We care about this location
					  final int bufferX = x - origin.x;
					  final int bufferY = y - origin.y;
					  
					  if (bufferX < 0 || bufferX >= width || bufferY < 0 || bufferY >= height) {
						  // Out of bounds, skip it
						  continue;
					  }
					  
					  final int currentColor = buffer[bufferY*width + bufferX];
					  if (currentColor == NOT_SEEN) {
						  if (team == Team.A) {
							  buffer[bufferY*width + bufferX] = SEEN_A;
						  } else if (team == Team.B){
							  buffer[bufferY*width + bufferX] = SEEN_B;
						  }
					  } else if (
							  (currentColor == SEEN_A && team == Team.B) ||
							  (currentColor == SEEN_B && team == Team.A)
							  ) {
						  buffer[bufferY*width + bufferX] = SEEN_BOTH;
					  }
				  }
			  }
		  }
	  }
  }
  
  protected Iterable<Map.Entry<Integer, DrawObject>> drawables =
    new Iterable<Map.Entry<Integer, DrawObject>>() {

    public Iterator<Map.Entry<Integer, DrawObject>> iterator() {
      return new UnitIterator();
    }
  };

  private class UnitIterator implements Iterator<Map.Entry<Integer, DrawObject>> {

    private Iterator<Map.Entry<Integer, DrawObject>> it =
      groundUnits.entrySet().iterator();
    private boolean ground = true;

    public boolean hasNext() {
      return it.hasNext() || (ground && !airUnits.isEmpty());
    }

    public Map.Entry<Integer, DrawObject> next() {
      if (!it.hasNext() && ground) {
        ground = false;
        it = airUnits.entrySet().iterator();
      }
      return it.next();
    }

    public void remove() {
      it.remove();
    }
  };

  protected class Link {
    public MapLocation from;
    public MapLocation to;
    public boolean [] connected;

    public Link(MapLocation from, MapLocation to) {
      this.from = from;
      this.to = to;
      connected = new boolean [2];
    }

    public Link(Link l) {
      this.from = l.from;
      this.to = l.to;
      this.connected = new boolean [2];
      System.arraycopy(l.connected,0,this.connected,0,2);
    }
  }

  private Map<MapLocation, List<MapLocation>> neighbors = null;
  private Map<MapLocation, Team> nodeTeams = new HashMap<MapLocation,Team>();
  protected List<Link> links = new ArrayList<Link>();


  public AbstractDrawState() {
    hqs = new EnumMap<Team, DrawObject>(Team.class);
    towers.put(Team.A, new HashMap<Integer, DrawObject>());
    towers.put(Team.B, new HashMap<Integer, DrawObject>());
    totalRobotTypeCount.put(Team.A, new EnumMap<RobotType, Integer>(RobotType.class));
    totalRobotTypeCount.put(Team.B, new EnumMap<RobotType, Integer>(RobotType.class));
    teamSupplyLevels = new EnumMap<Team, Double>(Team.class);
  }

  protected synchronized void copyStateFrom(AbstractDrawState<DrawObject> src) {
      currentRound = src.currentRound;
      
      groundUnits.clear();
      for(Map<Integer, DrawObject> towerMap : towers.values()) {
	  for(Integer id : towerMap.keySet()) {
	      towerMap.put(id, null);
	  }
      }
      for (Map.Entry<Integer, DrawObject> entry : src.groundUnits.entrySet()) {
        DrawObject copy = createDrawObject(entry.getValue());
        groundUnits.put(entry.getKey(), copy);
        tryAddHQ(copy);
	tryAddTower(copy);
      }
      airUnits.clear();
      for (Map.Entry<Integer, DrawObject> entry : src.airUnits.entrySet()) {
        DrawObject copy = createDrawObject(entry.getValue());
        airUnits.put(entry.getKey(), copy);
      }

      

      mineLocs.clear();
      mineLocs.putAll(src.mineLocs);
        
      locationOre.clear();
      locationOre.putAll(src.locationOre);
        
      fluxDeposits.clear();
      for (Map.Entry<Integer, FluxDepositState> entry : src.fluxDeposits.entrySet()) {
        fluxDeposits.put(entry.getKey(), new FluxDepositState(entry.getValue()));
      }
      coreIDs = src.coreIDs;
      stats = src.stats;

      nodeTeams = new HashMap<MapLocation,Team>(src.nodeTeams);
	
      neighbors = src.neighbors;

      links.clear();
      for(Link l : src.links) {
        links.add(new Link(l));
      }
	
      if (src.gameMap != null) {
        gameMap = src.gameMap;
      }

      for (int x=0; x<teamResources.length; x++)
        teamResources[x] = src.teamResources[x];
      for (int t = 0; t < researchProgress.length; t++)
        for (int r = 0; r < researchProgress[t].length; r++)
          researchProgress[t][r] = src.researchProgress[t][r];

      indicatorDots = src.indicatorDots;
      indicatorLines = src.indicatorLines;
      
      totalRobotTypeCount.put(Team.A, new EnumMap<RobotType, Integer>(src.totalRobotTypeCount.get(Team.A)));
      totalRobotTypeCount.put(Team.B, new EnumMap<RobotType, Integer>(src.totalRobotTypeCount.get(Team.B)));
      
      teamSupplyLevels.put(Team.A, src.teamSupplyLevels.get(Team.A));
      teamSupplyLevels.put(Team.B, src.teamSupplyLevels.get(Team.B));
      
      mapMemoryImage = new DrawableMapMemory(src.mapMemoryImage);
    }

  public DrawObject getHQ(Team t) {
    return hqs.get(t);
  }
	
    public Map<Integer, DrawObject> getTowers(Team t) {
	return towers.get(t);
    }

  public int[] getRobotCounts(Team t) {
    // naive way for now...
    int[] counts = new int[RobotType.values().length];
    for (Map.Entry<Integer, DrawObject> e : drawables) {
      if (e.getValue().getTeam() == t)
        counts[e.getValue().getType().ordinal()]++;
    }
    return counts;
  }
  
  public void incrementRobotTypeCount(Team team, RobotType type) {
    if (totalRobotTypeCount.get(team).containsKey(type)) {
        totalRobotTypeCount.get(team).put(type, totalRobotTypeCount.get(team).get(type) + 1);
    } else {
        totalRobotTypeCount.get(team).put(type, 1);
    }
  }
  
  public void decrementRobotTypeCount(Team team, RobotType type){
  	totalRobotTypeCount.get(team).put(type, totalRobotTypeCount.get(team).get(type) - 1);
  }
  
  public int getRobotTypeCount(Team team, RobotType type){
  	if (totalRobotTypeCount.get(team).containsKey(type)) {
  		return totalRobotTypeCount.get(team).get(type);
  	} else {
  		return 0;
  	}
  }

  public DrawObject getPowerCore(Team t) {
    int id = coreIDs[t.ordinal()];
    if(id!=0)
      return getRobot(id);
    else
      return null;
  }
	
  public Set<MapLocation> getEncampmentLocations() {
    return encampments;
  }

  protected Iterable<Map.Entry<Integer, DrawObject>> getDrawableSet() {
    return drawables;
  }

  protected double getOreAtLocation(MapLocation loc) {
    if (locationOre.containsKey(loc)) {
      return locationOre.get(loc);
    } else {
      return 0.0;
    }
  }

  protected DrawObject getRobot(int id) {
    DrawObject obj = groundUnits.get(id);
    if (obj == null) {
      obj = airUnits.get(id);
      assert obj != null : "Robot #" + id + " not found";
    }
    return obj;
  }

  protected void removeRobot(int id) {
    DrawObject previous = groundUnits.remove(id);
    if (previous == null) {
      previous = airUnits.remove(id);
      assert previous != null : "Robot #" + id + " not found";
    }
  }

  protected void putRobot(int id, DrawObject unit) {
    DrawObject previous = groundUnits.put(id, unit);
    assert previous == null : "Robot #" + id + " already exists";
  }

  protected void tryAddHQ(DrawObject hq) {
    if (hq.getType() == RobotType.HQ)
      hqs.put(hq.getTeam(),hq);
  }
  protected void tryAddTower(DrawObject t) {
      if (t.getType() == RobotType.TOWER) {
	  towers.get(t.getTeam()).put(t.getID(), t);
      }
  }


  public RoundStats getRoundStats() {
    return stats;
  }

    public int getCurrentRound() {
	return currentRound;
    }

  public void setGameMap(GameMap map) {
    gameMap = new GameMap(map);
    origin = gameMap.getMapOrigin();
    mapMemoryImage = new DrawableMapMemory(origin, map.getWidth(), map.getHeight());
  }

  public GameMap getGameMap() {
    return gameMap;
  }


    protected void preUpdateRound() {
	currentRound++;
    }

  protected void postUpdateRound() {
    for (Iterator<Map.Entry<Integer, DrawObject>> it = drawables.iterator();
         it.hasNext();) {
      DrawObject obj = it.next().getValue();
      obj.updateRound();
      if (!obj.isAlive()) {
	  it.remove();
	  if(obj.getType() == RobotType.TOWER) {
	      towers.get(obj.getTeam()).put(obj.getID(), null);
	  }
        //if (obj.getType() == RobotType.ARCHON) {
        //	(obj.getTeam() == Team.A ? archonsA : archonsB).remove(obj);
        //}
      }
      //if (obj.getType() == RobotType.WOUT) {
      //	mineFlux(obj);
      //}
    }
    indicatorDots = newIndicatorDots.toArray(new IndicatorDotSignal [newIndicatorDots.size()]);
    indicatorLines = newIndicatorLines.toArray(new IndicatorLineSignal [newIndicatorLines.size()]);
    newIndicatorDots.clear();
    newIndicatorLines.clear();
  }

  public void visitAttackSignal(AttackSignal s) {
    DrawObject robot = getRobot(s.getRobotID());
    robot.setDirection(robot.getLocation().directionTo(s.getTargetLoc()));
    robot.setAttacking(s.getTargetLoc());

  }
    
    public void visitBashSignal(BashSignal s) {
	DrawObject robot = getRobot(s.getRobotID());
	robot.setAttacking(robot.getLocation());
    }

  public void visitBroadcastSignal(BroadcastSignal s) {
    getRobot(s.getRobotID()).setBroadcast();
  }

  public void visitSelfDestructSignal(SelfDestructSignal s) {
    DrawObject robot = getRobot(s.getRobotID());
    robot.setSuiciding(true);
  }

  public void visitDeathSignal(DeathSignal s) {
      DrawObject robot = getRobot(s.getObjectID());      
    int team = robot.getTeam().ordinal();

    if (team < 2) {
      teamHP[team] -= getRobot(s.getObjectID()).getEnergon();
    }
    decrementRobotTypeCount(robot.getTeam(), robot.getRobotType());
    robot.destroyUnit();
  }

  public void visitTeamOreSignal(TeamOreSignal s) {
    for (int x=0; x<teamResources.length; x++)
      teamResources[x] = s.ore[x];
  }

  public void visitTransferSupplySignal(TransferSupplySignal s) {
    DrawObject from = getRobot(s.fromID);
    DrawObject to = getRobot(s.toID);
    from.setSupplyTransfer(to,s.amount);
  }

  public void visitIndicatorStringSignal(IndicatorStringSignal s) {
    if (!RenderConfiguration.isTournamentMode()) {
      getRobot(s.getRobotID()).setString(s.getStringIndex(), s.getNewString());
    }
  }

  public void visitIndicatorDotSignal(IndicatorDotSignal s) {
    newIndicatorDots.add(s);
  }

  public void visitIndicatorLineSignal(IndicatorLineSignal s) {
    newIndicatorLines.add(s);
  }

  public void visitControlBitsSignal(ControlBitsSignal s) {
    getRobot(s.getRobotID()).setControlBits(s.getControlBits());
        
  }

  public void visitMovementOverrideSignal(MovementOverrideSignal s) {
    getRobot(s.getRobotID()).setLocation(s.getNewLoc());
  }
  
  public void visitMovementSignal(MovementSignal s) {
    DrawObject obj = getRobot(s.getRobotID());
    MapLocation oldloc = obj.loc;
    obj.setLocation(s.getNewLoc());
    obj.setDirection(oldloc.directionTo(s.getNewLoc()));
    obj.setMoving(s.isMovingForward(), s.getDelay());
    
    // Update fog o' war, things might have changed:
    if (mapMemoryImage != null)
    	mapMemoryImage.rememberLocation(obj.getTeam(), s.getNewLoc(), obj.getType().sensorRadiusSquared);
  }

  public void visitCastSignal(CastSignal s) {
    //TODO(npinsker): update this with various spells
    
    DrawObject obj = getRobot(s.getRobotID());
    MapLocation oldloc = obj.loc;
    obj.setLocation(s.getTargetLoc());
    obj.setDirection(oldloc.directionTo(s.getTargetLoc()));
  }

  // Ugly hack, see visitMineSignal
  	public abstract void addMiningAnim(final MapLocation loc, final float amount);
  
    public void visitMineSignal(final MineSignal s) {
    	// Animate mine signals.
    	// NOTE: this isn't done through the normal animation interface.
    	// Why, you ask?
    	// Because s.getMinerID() is ALWAYS 0 for some reason, and I can't seem to fix it.
    	// TODO UPDATE to use normal animation interface if minesignals start to include ids!
    	
    	// Assume beavers, if type is null
    	final RobotType type = s.getMinerType() != null? s.getMinerType() : RobotType.BEAVER;
    	final float oreDivisor = type == RobotType.MINER? 4.0f : 20.0f;
    	// UPDATE THIS if mining rate changes!
    	final float oreAmount = Math.max(Math.min((float) getOreAtLocation(s.getMineLoc())/oreDivisor, 2.5f), 0.2f);
    	addMiningAnim(s.getMineLoc(), oreAmount);
    }

  public DrawObject spawnRobot(SpawnSignal s) {
    DrawObject spawn = createDrawObject(s.getType(), s.getTeam(), s.getRobotID());
    spawn.setLocation(s.getLoc());
//        spawn.setDirection(s.getDirection());
    spawn.setDirection(Direction.NORTH);
    spawn.setBuildDelay(s.getDelay());
    if (s.getParentID() != 0) {
	DrawObject parent = getRobot(s.getParentID());
	parent.setAction(s.getDelay(), ActionType.BUILDING);
    }
    putRobot(s.getRobotID(), spawn);
    tryAddHQ(spawn);
    tryAddTower(spawn);
    int team = getRobot(s.getRobotID()).getTeam().ordinal();
    if (team < 2) {
      teamHP[team] += getRobot(s.getRobotID()).getEnergon();
    }
		
    return spawn;
  }

  public void visitSpawnSignal(SpawnSignal s) {
    spawnRobot(s);
    incrementRobotTypeCount(s.getTeam(), s.getType());
    
    // Update fog o' war, new robot might have seen something:
    if (mapMemoryImage != null)
    	mapMemoryImage.rememberLocation(s.getTeam(), s.getLoc(), s.getType().sensorRadiusSquared);
  }

  public void visitBytecodesUsedSignal(BytecodesUsedSignal s) {
    int[] robotIDs = s.getRobotIDs();
    int[] bytecodes = s.getNumBytecodes();
    for (int i = 0; i < robotIDs.length; i++) {
      DrawObject robot = getRobot(robotIDs[i]);
      if (robot != null) robot.setBytecodesUsed(bytecodes[i]);
    }
        
  }

  public void visitHealthChange(HealthChangeSignal s){
    int[] robotIDs = s.getRobotIDs();
    double[] health = s.getHealth();
    for (int i = 0; i < robotIDs.length; i++) {
      DrawObject robot = getRobot(robotIDs[i]);
      if (robot != null) {
          robot.setEnergon(health[i]);
      }
    }
  }

  public void visitRobotInfoSignal(RobotInfoSignal s){
    int[] robotIDs = s.getRobotIDs();
    double[] coreDelays = s.getCoreDelays();
    double[] weaponDelays = s.getWeaponDelays();
    double[] supplyLevels = s.getSupplyLevels();
    
    double teamASupplies = 0;
    double teamBSupplies = 0;
    
    for (int i = 0; i < robotIDs.length; i++) {
      DrawObject robot = getRobot(robotIDs[i]);
      if (robot != null) {
          robot.setMovementDelay(coreDelays[i]);
          robot.setAttackDelay(weaponDelays[i]);
          robot.setSupplyLevel(supplyLevels[i]);
          
          if (robot.getTeam() == Team.A) {
        	  teamASupplies += supplyLevels[i];
          } else {
        	  teamBSupplies += supplyLevels[i];
          }
      }
    }
    teamSupplyLevels.put(Team.A, teamASupplies);
    teamSupplyLevels.put(Team.B, teamBSupplies);
  }

  public void visitXPSignal(XPSignal s) {
    getRobot(s.getRobotID()).setXP(s.getXP());
  }

  public void visitMissileCountSignal(MissileCountSignal s) {
    getRobot(s.getRobotID()).setMissileCount(s.getMissileCount());
  }

  public void visitLocationOreChangeSignal(LocationOreChangeSignal s) {
    locationOre.put(s.getLocation(), s.getOre());
  }
}

