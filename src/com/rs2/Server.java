package com.rs2;

import com.rs2.cache.Cache;
import com.rs2.cache.interfaces.RSInterface;
import com.rs2.cache.object.GameObjectData;
import com.rs2.cache.object.ObjectLoader;
import com.rs2.model.World;
import com.rs2.model.content.combat.CombatManager;
import com.rs2.model.content.minigames.GroupMiniGame;
import com.rs2.model.content.minigames.groupminigames.CastleWarsCounter;
import com.rs2.model.content.minigames.magetrainingarena.*;
import com.rs2.model.content.skills.fishing.FishingSpots;
import com.rs2.model.npcs.Npc;
import com.rs2.model.npcs.NpcLoader;
import com.rs2.model.players.GlobalGroundItem;
import com.rs2.model.players.HighscoresManager;
import com.rs2.model.players.Player;
import com.rs2.model.players.Player.LoginStages;
import com.rs2.model.players.item.ItemDefinition;
import com.rs2.model.players.item.ItemManager;
import com.rs2.model.tick.Tick;
import com.rs2.net.DedicatedReactor;
import com.rs2.net.packet.PacketManager;
import com.rs2.task.TaskScheduler;
import com.rs2.task.Task;
import com.rs2.util.*;
import com.rs2.util.clip.ObjectDef;
import com.rs2.util.clip.Rangable;
import com.rs2.util.clip.Region;
import com.rs2.util.plugin.PluginManager;
import com.rs2.util.sql.SQL;
import com.rs2.util.sql.SQLEngine;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The main core of RuneSource.
 *
 * @author blakeman8192
 */
public class Server implements Runnable {

	private static Server singleton;
	private final String host;
	private final int port;

	private final int cycleRate;

	private static long minutesCounter;

    public static GroupMiniGame castleWarsGroup = new GroupMiniGame(new CastleWarsCounter());

	private Selector selector;
	private InetSocketAddress address;
	private ServerSocketChannel serverChannel;
	private Misc.Stopwatch cycleTimer;
	private final Queue<Player> loginQueue = new ConcurrentLinkedQueue<Player>();
    
    private static final Queue<Player> disconnectedPlayers = new LinkedList<Player>();
    private static final TaskScheduler scheduler = new TaskScheduler();

	/**
	 * Creates a new Server.
	 *
	 * @param host
	 *            the host
	 * @param port
	 *            the port
	 * @param cycleRate
	 *            the cycle rate
	 */
	private Server(String host, int port, int cycleRate) {
		this.host = host;
		this.port = port;
		this.cycleRate = cycleRate;
	}

	/**
	 * The main method.
	 *
	 * @param args
	 */
	public static void main(String[] args) {

		String host = "127.0.0.1";//args[0];
		int port = 43594;
		int cycleRate = 600;

        //PlayerCleaner.start();
        //System.exit(0);

        if (host.equals("127.0..0.1")) {
            System.out.println("Starting live server!");
            Constants.DEVELOPER_MODE = false;
            Constants.MYSQL_ENABLED = false;
            Constants.SERVER_DEBUG = false;
            Constants.HIGHSCORES_ENABLED = false;
            Constants.ADMINS_CAN_INTERACT = false;
            Constants.RSA_CHECK = false;
            Constants.CLIENT_VERSION = 101;
        }

        //PlayerCleaner.start();
        //System.exit(0);

		setSingleton(new Server(host, port, cycleRate));

		new Thread(getSingleton()).start();
	}

    public static Queue<Player> getDisconnectedPlayers() {
        return disconnectedPlayers;
    }

    public void queueLogin(Player player) {
		loginQueue.add(player);
	}

	@Override
	public void run() {
		try {
			Thread.currentThread().setName("ServerEngine");
			System.setOut(new Misc.TimestampLogger(System.out));
			System.setErr(new Misc.TimestampLogger(System.err, "./data/err.log"));

			address = new InetSocketAddress(host, port);
			System.out.println("Starting " + Constants.SERVER_NAME + " on " + address + "...");

			// load shutdown hook
			Thread shutdownhook = new ShutDownHook();
			Runtime.getRuntime().addShutdownHook(shutdownhook);

			PacketManager.loadPackets();

            Cache.load();


            // load scripts
            Misc.loadScripts(new File("./data/ruby/"));


			// load all xstream related files.
			XStreamUtil.loadAllFiles();

			// item weights
			ItemDefinition.loadWeight();

            //interfaces
            RSInterface.load();

			// Load plugins
			PluginManager.loadPlugins();

			// Load regions
			ObjectDef.loadConfig();
			Region.load();
			Rangable.load();

			// Load objects
			ObjectLoader objectLoader = new ObjectLoader();
			objectLoader.load();

			GameObjectData.init();

			// load combat manager
			CombatManager.init();

			// Load minute timer
			startMinutesCounter();

			// global drops
			//GlobalGroundItem.initialize();

			// load npc drops
			Npc.loadNpcDrops();
			
			// mage arena timers
			 AlchemistPlayground.loadAlchemistPlayGround();
			 EnchantingChamber.loadEnchantingChamber();
			 CreatureGraveyard.loadCreatureGraveyard();

			// spawning world fishing spots
			FishingSpots.spawnFishingSpots();

			NpcLoader.loadAutoSpawn("./data/npcs/spawn-config.cfg");

            HighscoresManager.load();


			// Start up and get a'rollin!
			startup();
			System.out.println("Online!");
			while (!Thread.interrupted()) {
				try {
					cycle();
					sleep();
				} catch (Exception ex) {
					PlayerSave.saveAllPlayers();
					ex.printStackTrace();
				}
			}
			scheduler.schedule(new Task() {
				@Override
				protected void execute() {
					if (Thread.interrupted()) {
						PlayerSave.saveAllPlayers();
						stop();
						return;
					}
					try {
						cycle();
					} catch (Exception ex) {
						PlayerSave.saveAllPlayers();
						ex.printStackTrace();
						stop();
					}
				}
			});
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		PluginManager.close();
	}

	/**
	 * Starts the server up.
	 *
	 * @throws java.io.IOException
	 */
	private void startup() throws Exception {
		// Initialize the networking objects.
		selector = Selector.open();
		serverChannel = ServerSocketChannel.open();
		DedicatedReactor.setInstance(new DedicatedReactor(Selector.open()));
		DedicatedReactor.getInstance().start();

		// ... and configure them!
		serverChannel.configureBlocking(false);
		serverChannel.socket().bind(address);

		synchronized (DedicatedReactor.getInstance()) {
			DedicatedReactor.getInstance().getSelector().wakeup();
			serverChannel.register(DedicatedReactor.getInstance().getSelector(), SelectionKey.OP_ACCEPT);
		}

		// Finally, initialize whatever else we need.
		cycleTimer = new Misc.Stopwatch();
	}

	/**
	 * Accepts any incoming connections.
	 *
	 * @throws java.io.IOException
	 */
	public static void accept(SelectionKey key) throws IOException {
		ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

		// Accept the socket channel.
		SocketChannel channel = serverChannel.accept();
		if (channel == null) {
			return;
		}

		// Make sure we can allow this connection.
		if (!HostGateway.enter(channel.socket().getInetAddress().getHostAddress())) {
			channel.close();
			return;
		}

		// Set up the new connection.
		channel.configureBlocking(false);
		SelectionKey newKey = channel.register(key.selector(), SelectionKey.OP_READ);
		Player player = new Player(newKey);
		newKey.attach(player);
	}

	/**
	 * Performs a server cycle.
	 */
	private void cycle() {
		int loggedIn = 0;
        Benchmark b = Benchmarks.getBenchmark("loginQueue");
        b.start();
		while (!loginQueue.isEmpty() && loggedIn++ < 50) {
			Player player = loginQueue.poll();
			try {
				player.finishLogin();
				player.setLoginStage(LoginStages.LOGGED_IN);
			} catch (Exception ex) {
				//ex.printStackTrace();
				player.disconnect();
			}
		}
        b.stop();

        b = Benchmarks.getBenchmark("handleNetworkPackets");
        b.start();
		// Handle all network events.
		try {
			selector.selectNow();
			for (SelectionKey selectionKey : selector.selectedKeys()) {
				if (selectionKey.isValid()) {
					if (selectionKey.isReadable()) {
						// Tell the client to handle the packet.
						PacketManager.handleIncomingData((Player) selectionKey.attachment());
					}
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
        b.stop();

		// Next, perform game processing.
		try {
			PluginManager.tick();
			World.process();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
        b = Benchmarks.getBenchmark("disconnectingPlayers");
        b.start();
        synchronized (disconnectedPlayers) {
            for (Iterator<Player> players = disconnectedPlayers.iterator(); players.hasNext();) {
                Player player = players.next();
                if (player.logoutDisabled())
                    continue;
                player.logout();
                players.remove();
            }
        }
        b.stop();
	}

	/**
	 * Sleeps for the cycle delay.
	 *
	 * @throws InterruptedException
	 */
	private void sleep() {
		try {
			long sleepTime = cycleRate - cycleTimer.elapsed();
            boolean sleep = sleepTime > 0 && sleepTime < 600;
            for (int i = 0; i < PacketManager.SIZE; i++) {
                Benchmark b = PacketManager.packetBenchmarks[i];
                if (!sleep && b.getTime() > 0)
                    System.out.println("Packet "+i+"["+PacketManager.packets[i].getClass().getSimpleName()+"] took "+b.getTime()+" ms.");
                b.reset();
            }
			if (sleep) {
                Benchmarks.resetAll();
				//System.out.println("[ENGINE]: Sleeping for " + sleepTime + "ms");
				Thread.sleep(sleepTime);
			} else {
				// The server has reached maximum load, players may now lag.
				long cycle = (100 + Math.abs(sleepTime) / (cycleRate / 100));
				/*if (cycle > 999) {
					initiateRestart();
				}*/
				System.out.println("[WARNING]: Server load: " + cycle + "%!");
                Benchmarks.printAll();
                Benchmarks.resetAll();
                for (int i = 0; i < 5; i++)
                    System.out.println("");
			}
		} catch (Exception ex) {
            ex.printStackTrace();

		} finally {
			cycleTimer.reset();
		}
	}

	@SuppressWarnings("unused")
	private void initiateRestart() {
		for (Player player : World.getPlayers()) {
            if (player == null || player.getIndex() == -1)
                continue;
            player.getActionSender().sendUpdateServer(30);
        }
		new ShutdownWorldProcess(30).start();
	}

	/**
	 * Starts the minute counter
	 */
	private void startMinutesCounter() {
		try {
			BufferedReader minuteFile = new BufferedReader(new FileReader("./data/minutes.log"));
			Server.minutesCounter = Integer.parseInt(minuteFile.readLine());
		} catch (Exception e) {
			e.printStackTrace();
		}

		World.submit(new Tick(25) {
		    @Override public void execute() {
		        setMinutesCounter(getMinutesCounter() + 1);
                for (Player player : World.getPlayers()) {
                  if (player == null) {
                      continue;
                  }
                  player.getAllotment().doCalculations();
                  player.getFlowers().doCalculations();
                  player.getHerbs().doCalculations();
                  player.getHops().doCalculations();
                  player.getBushes().doCalculations();
                  player.getTrees().doCalculations();
                  player.getFruitTrees().doCalculations();
                  player.getSpecialPlantOne().doCalculations();
                  player.getSpecialPlantTwo().doCalculations(); //lowering all player items timer
	              ItemManager.getInstance().lowerAllItemsTimers(player);
                }
		    }
        });

	}

	public static void setMinutesCounter(long minutesCounter) {
		Server.minutesCounter = minutesCounter;
		try {
			BufferedWriter minuteCounter = new BufferedWriter(new FileWriter("./data/minutes.log"));
			minuteCounter.write(Long.toString(getMinutesCounter()));
			minuteCounter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static long getMinutesCounter() {
		return minutesCounter;
	}

	/**
	 * Sets the server singleton object.
	 * 
	 * @param singleton
	 *            the singleton
	 */
	public static void setSingleton(Server singleton) {
		if (Server.singleton != null) {
			throw new IllegalStateException("Singleton already set!");
		}
		Server.singleton = singleton;
	}

	/**
	 * Gets the server singleton object.
	 * 
	 * @return the singleton
	 */
	public static Server getSingleton() {
		return singleton;
	}

	/**
	 * Gets the selector.
	 * 
	 * @return The selector
	 */
	public Selector getSelector() {
		return selector;
	}

	public int getPort() {
		return port;
	}

}
