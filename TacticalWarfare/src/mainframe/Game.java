package mainframe;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.*;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.esotericsoftware.kryonet.Server;

import packets.KeyPress;
import packets.MapData;
import packets.Message;
import packets.MouseClick;
import packets.PlayerInfo;
import packets.ProjectileInfo;
import packets.ProjectilePositions;
import packets.TileInfo;
import packets.UnitInfo;
import packets.UnitPositions;
import rendering.Bitmap;
import rendering.GameTextures;
import rendering.Model;

import java.io.IOException;
import java.nio.*;

import static org.lwjgl.glfw.Callbacks.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Game extends Listener{
	
	int WINDOW_WIDTH = 840;
	int WINDOW_HEIGHT = 640;
	
	int gameScreenWidth = 640;
	int gameScreenHeight = 640;
	
	public double viewX = 0;
	public double viewY = 0;
	public double cameraSpeed = 10;
	public double cameraWidth = 840;
	public double cameraHeight = 640;
	
	public int windowXOffset = 0;
	public int windowYOffset = 0;
	
	boolean panLeft = false;
    boolean panRight = false;
    boolean panUp = false;
    boolean panDown = false;
    boolean unitTracking = false;
    
	int mapWidth;
	int mapHeight;
	int[][] map;
	Tile[][] tiles = null;
	int unitId = 0;
	
	int tick = 0;
	int ticksPerDay = 24000;
	float lightLevel = 1;
	
	ArrayList<Player> players = new ArrayList<>();
	ArrayList<Unit> units = new ArrayList<>();
	ArrayList<Projectile> projectiles = new ArrayList<>();
	ArrayList<Integer> selectedUnitsId = new ArrayList<>();
	
	boolean clientRecieved = false;
	boolean spacePressed = false;
	
	int gameState = 3;
	
	int red_flags = 0;
	int blue_flags = 0;
	int red_score = 0;
	int blue_score = 0;
	
//	Player debug = new Player(this, "debug", 0);
	int serverPlayerId = 0;
	
	// The window handle
	private long window;
	
	static GameLogic gamelogic = new GameLogic();
	static GameTextures gametextures;
	
	//networking
	static Server server;
	static int tcpPort = 27960;
	static int udpPort = 27960;
	
	Random random = new Random();
	
	static Bitmap bitmap;
	
	public void run() throws IOException {
		//create server
		server = new Server(32768, 32768);
		//register packets
		server.getKryo().register(java.util.ArrayList.class);
		server.getKryo().register(double[].class);
		server.getKryo().register(int[].class);
		server.getKryo().register(int[][].class);
		server.getKryo().register(UnitPositions.class);
		server.getKryo().register(ProjectilePositions.class);
		server.getKryo().register(PlayerInfo.class);
		server.getKryo().register(UnitInfo.class);
		server.getKryo().register(ProjectileInfo.class);
		server.getKryo().register(MouseClick.class);
		server.getKryo().register(KeyPress.class);
		server.getKryo().register(MapData.class);
		server.getKryo().register(Message.class);
		server.getKryo().register(TileInfo.class);
		server.bind(tcpPort, udpPort);
		
		//start server
		server.start();
		System.out.println("server is on");
		
		server.addListener(this);
		
		initGLFW();
		loop();

		// Free the window callbacks and destroy the window
		glfwFreeCallbacks(window);
		glfwDestroyWindow(window);

		// Terminate GLFW and free the error callback
		glfwTerminate();
		glfwSetErrorCallback(null).free();
		server.stop();
	}
	
	//when a connection is recieved
	@Override
	public void connected(Connection c){
		System.out.println("recieved connection from " + c.getRemoteAddressTCP().getHostString());
		//send map
		server.sendToTCP(c.getID(), new MapData(map, tick));
		//add previous players
		for (int i = 0; i < players.size(); i++) {
			Player previous = players.get(i);
			server.sendToTCP(c.getID(), new PlayerInfo(1, previous.getTeam(), previous.getId()));
		}
		Player newplayer = new Player("derp", c.getID());
		players.add(newplayer);
		server.sendToAllTCP(new PlayerInfo(1, newplayer.getTeam(), newplayer.getId()));
		String unitTeam = "blue";
		double unitX = 100*(random.nextInt(6) + 1);
		double unitY = 100*(random.nextInt(6) + 1);
		double unitAngle = random.nextInt(360);
		System.out.println(unitId + " " + newplayer.getId());
		units.add(createUnit(newplayer.getId(), unitTeam, unitX, unitY, unitAngle, 2));
		System.out.println("finished recieving client " + c.getID());
	}
	
	public void updateClients(){
		for (int i = 0; i < players.size(); i++) {
			server.sendToAllTCP(new UnitPositions(units));
			server.sendToAllTCP(new ProjectilePositions(projectiles));
		}
	}
	
	//when an object is recieved
	@Override
	public void received(Connection c, Object obj) {
		if(obj instanceof MouseClick){
			MouseClick click = (MouseClick) obj;
			moveOrder(click.getX(), click.getY(), click.getUnitIds());
		}
		else if(obj instanceof KeyPress) {
			KeyPress key = (KeyPress) obj;
			if(key.getKey() == GLFW_KEY_SPACE) {
				fireProjectile(key.getUnitIds());
			}
		}
	}
	
	//when a connection disconnects
	@Override
	public void disconnected(Connection c){
		System.out.println("A client disconnected");
//		//remove player that disconnected
		for (int p = 0; p < players.size(); p++) {
			if(players.get(p).getId() == c.getID()){
				server.sendToAllTCP(new PlayerInfo(2, players.get(p).getTeam(), players.get(p).getId()));
				players.remove(p);
				break;
			}
		}
		//remove units of player that disconnected
		for (int u = 0; u < units.size(); u++) {
			if(units.get(u).getOwnerId() == c.getID()){
				units.remove(u);
				u--;
			}
		}
	}

	private void initGLFW() {
		// Setup an error callback. The default implementation
		// will print the error message in System.err.
		GLFWErrorCallback.createPrint(System.err).set();

		// Initialize GLFW. Most GLFW functions will not work before doing this.
		if ( !glfwInit() )
			throw new IllegalStateException("Unable to initialize GLFW");

		// Configure GLFW
		glfwDefaultWindowHints(); // optional, the current window hints are already the default
		glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
		glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

		// Create the window
		window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, "My World (Server)", NULL, NULL);
		if ( window == NULL )
			throw new RuntimeException("Failed to create the GLFW window");

		// Setup a key callback. It will be called every time a key is pressed, repeated or released.
		glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
			if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
				glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
			if ( key == GLFW_KEY_MINUS && action == GLFW_PRESS )
				if(gameState == 3){
					updateZoomLevel(true);
				}
			if ( key == GLFW_KEY_EQUAL && action == GLFW_PRESS )
				if(gameState == 3){
					updateZoomLevel(false);
				}
			if ( key == GLFW_KEY_LEFT && action == GLFW_PRESS )
				panLeft = true;
			if ( key == GLFW_KEY_LEFT && action == GLFW_RELEASE )
				panLeft = false;
			
			if ( key == GLFW_KEY_RIGHT && action == GLFW_PRESS )
				panRight = true;
			if ( key == GLFW_KEY_RIGHT && action == GLFW_RELEASE )
				panRight = false;
			
			if ( key == GLFW_KEY_UP && action == GLFW_PRESS )
				panUp = true;
			if ( key == GLFW_KEY_UP && action == GLFW_RELEASE )
				panUp = false;
			
			if ( key == GLFW_KEY_DOWN && action == GLFW_PRESS )
				panDown = true;
			if ( key == GLFW_KEY_DOWN && action == GLFW_RELEASE )
				panDown = false;
			
			if ( key == GLFW_KEY_A && action == GLFW_PRESS )
				panLeft = true;
			if ( key == GLFW_KEY_A && action == GLFW_RELEASE )
				panLeft = false;
			
			if ( key == GLFW_KEY_D && action == GLFW_PRESS )
				panRight = true;
			if ( key == GLFW_KEY_D && action == GLFW_RELEASE )
				panRight = false;
			
			if ( key == GLFW_KEY_W && action == GLFW_PRESS )
				panUp = true;
			if ( key == GLFW_KEY_W && action == GLFW_RELEASE )
				panUp = false;
			
			if ( key == GLFW_KEY_S && action == GLFW_PRESS )
				panDown = true;
			if ( key == GLFW_KEY_S && action == GLFW_RELEASE )
				panDown = false;
			if ( key == GLFW_KEY_T && action == GLFW_PRESS ) {
				unitTracking = !unitTracking;
			}
			if ( key == GLFW_KEY_SPACE && action == GLFW_PRESS )
				fireProjectile(selectedUnitsId);
		});
		//mouse clicks
		glfwSetMouseButtonCallback (window, (window, button, action, mods) -> {
			DoubleBuffer xpos = BufferUtils.createDoubleBuffer(3);
			DoubleBuffer ypos = BufferUtils.createDoubleBuffer(3);
			glfwGetCursorPos(window, xpos, ypos);
			//convert the glfw coordinate to our coordinate system
			xpos.put(0, Math.min(Math.max(xpos.get(0), windowXOffset), WINDOW_WIDTH + windowXOffset));
			ypos.put(0, Math.min(Math.max(ypos.get(0), windowYOffset), WINDOW_HEIGHT + windowYOffset));
			//relative camera coordinates
			xpos.put(1, getWidthScalar() * (xpos.get(0) - windowXOffset) + viewX);
			ypos.put(1, getHeightScalar() * (ypos.get(0) - windowYOffset) + viewY);
			//true window coordinates
			xpos.put(2, xpos.get(0) - windowXOffset);
			ypos.put(2, ypos.get(0) - windowYOffset);
			if ( button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
				for (int u = 0; u < units.size(); u++) {
					if(units.get(u).getOwnerId() == serverPlayerId && gamelogic.distance(xpos.get(1), ypos.get(1), units.get(u).getX(), units.get(u).getY()) <= 30){
						boolean selectUnit = true;
						for (int i = 0; i < selectedUnitsId.size(); i++) {
							if(selectedUnitsId.get(i) == units.get(u).getId()){
								selectedUnitsId.remove(i);
								selectUnit = false;
								break;
							}
						}
						if(selectUnit){
							selectedUnitsId.add(units.get(u).getId());
						}
					}
				}
			}
			else if ( button == GLFW_MOUSE_BUTTON_RIGHT && action == GLFW_PRESS) {
				moveOrder(xpos.get(1), ypos.get(1), selectedUnitsId);
			}
		});

		// Get the thread stack and push a new frame
		try ( MemoryStack stack = stackPush() ) {
			IntBuffer pWidth = stack.mallocInt(1); // int*
			IntBuffer pHeight = stack.mallocInt(1); // int*

			// Get the window size passed to glfwCreateWindow
			glfwGetWindowSize(window, pWidth, pHeight);

			// Get the resolution of the primary monitor
			GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

			// Center the window
			glfwSetWindowPos(
				window,
				(vidmode.width() - pWidth.get(0)) / 2,
				(vidmode.height() - pHeight.get(0)) / 2
			);
		} // the stack frame is popped automatically

		// Make the OpenGL context current
		glfwMakeContextCurrent(window);
		// Enable v-sync
		glfwSwapInterval(1);

		// Make the window visible
		glfwShowWindow(window);
	}

	private void loop() {
		// This line is critical for LWJGL's interoperation with GLFW's
		// OpenGL context, or any context that is managed externally.
		// LWJGL detects the context that is current in the current thread,
		// creates the GLCapabilities instance and makes the OpenGL
		// bindings available for use.
		GL.createCapabilities();
		
		projectTrueWindowCoordinates();

		// Set the clear color
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		
		//Enable transparency
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		
		gametextures = new GameTextures();
		
		bitmap = new Bitmap();
		
		generateMap();
//		loadUnits();
		Player newplayer = new Player("derp", serverPlayerId);
		players.add(newplayer);
		server.sendToAllTCP(new PlayerInfo(1, newplayer.getTeam(), newplayer.getId()));
		String unitTeam = "red";
//		double unitX = 100*(random.nextInt(6) + 1);
//		double unitY = 100*(random.nextInt(6) + 1);
		double unitAngle = random.nextInt(360);
		Unit serverUnit = createUnit(newplayer.getId(), unitTeam, 100, 100, unitAngle, 1);
		units.add(serverUnit);
		

		
		final double[] textureCoords = {0, 0, 0, 1, 1, 0, 1, 1};
		final int[] indices = {0, 1, 2, 2, 1, 3};
		final double[] placeholder = {0, 0, 0, 0, 0, 0, 0, 0};
		
		Model model = new Model(placeholder, textureCoords, indices);
	
		// Run the rendering loop until the user has attempted to close
		// the window or has pressed the ESCAPE key.
		while ( !glfwWindowShouldClose(window) ) {
			glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

			// Poll for window events. The key callback above will only be
			// invoked during this call.
			glfwPollEvents();
			
			updateCapturePoints();
						
			updateClients();
			
			glEnable(GL_TEXTURE_2D);
			
			projectRelativeCameraCoordinates();
			
			//display tiles
			for (int i = 0; i < tiles.length; i++) {
				for (int j = 0; j < tiles[0].length; j++) {
					tiles[i][j].setTexture();
					model.render(tiles[i][j].getVertices());
				}
			}
			
			//display units
			for (Unit u : units) {
				int[] tile = currentTile(u.getX(),u.getY());
				u.setTerrainMovement(tiles[tile[0]][tile[1]].getMovement());
				u.update();
				gametextures.loadTexture(u.getColor());
				model.render(u.getVertices());
			}
			
			//display projectiles
			for (int p = 0; p < projectiles.size(); p++) {
				Projectile current = projectiles.get(p);
				if(current.setPoints() == false){
					projectiles.remove(p);
					p--;
				}
				else {
					gametextures.loadTexture(current.getColor());
					model.render(current.getVertices());
					
				}
			}
			
			//display glow on selected units
			gametextures.loadTexture(0);
			for (Unit u : units) {
				for (int i = 0; i < selectedUnitsId.size(); i++) {
					if(selectedUnitsId.get(i) == u.getId()){
						model.render(u.getOutlineVertices());
					}
				}
			}
			
			projectTrueWindowCoordinates();
			gametextures.loadTexture(11);
			model.render(gameScreenWidth, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
			
			gametextures.loadTexture(12);
			model.render(gameScreenWidth + 10, 100, gameScreenWidth + 60, 150);
			bitmap.drawNumber(gameScreenWidth + 70, 110, gameScreenWidth + 95, 140, red_score);
			
			
			gametextures.loadTexture(13);
			model.render(gameScreenWidth + 10, 160, gameScreenWidth + 60, 210);
			bitmap.drawNumber(gameScreenWidth + 70, 170, gameScreenWidth + 95, 200, blue_score);
			
			glDisable(GL_TEXTURE_2D);
			
			tick++;
			if(tick > ticksPerDay) {
				tick = 0;
			}
			else if(tick < 2000) {
				lightLevel = Math.abs(0.5f - (float) tick/4000);
			}
			else if(tick < 12000) {
				lightLevel = 0;
			}
			else if(tick < 14000) {
				lightLevel = ((float) tick-12000) / 4000;
			}
			else {
				lightLevel = 0.5f;
			}
			
			if(tick % 50 == 0) {
				red_score += red_flags;
				blue_score += blue_flags;
			}
			
			glColor4f(0f, 0f, 0f, lightLevel);
			
			gametextures.loadTexture(-1);
			model.render(new double[] {0, 0, 0, gameScreenHeight, gameScreenWidth, 0, gameScreenWidth, gameScreenHeight});
			
			glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
			
			checkProjectiles();
			respawnUnits();
			
			if(unitTracking && selectedUnitsId.size() != 0) {
				double avgX = 0;
				double avgY = 0;
				for (Unit u : units) {
					for (int i = 0; i < selectedUnitsId.size(); i++) {
						if(selectedUnitsId.get(i) == u.getId()){
							avgX += u.getX();
							avgY += u.getY();
						}
					}
				}
				avgX /= selectedUnitsId.size();
				avgY /= selectedUnitsId.size();
						
				viewX = Math.min(gameScreenWidth - cameraWidth * (double) gameScreenWidth / (double) WINDOW_WIDTH,
						Math.max(0, avgX - cameraWidth/2));
				viewY = Math.min(gameScreenHeight - cameraHeight * (double) gameScreenHeight / (double) WINDOW_HEIGHT,
						Math.max(0, avgY - cameraHeight/2));
			}
			//move camera
			if (panLeft) {
				viewX = Math.max(0, viewX - cameraWidth / 30);
				unitTracking = false;
			}
			if (panRight) {
				viewX = Math.min(gameScreenWidth - cameraWidth * (double) gameScreenWidth / (double) WINDOW_WIDTH, viewX + cameraWidth / 30);
				unitTracking = false;
			}
			if (panDown) {
				viewY = Math.min(gameScreenHeight - cameraHeight * (double) gameScreenHeight / (double) WINDOW_HEIGHT, viewY + cameraHeight / 30);
				unitTracking = false;
			}
			if (panUp) {
				viewY = Math.max(0, viewY - cameraHeight / 30);
				unitTracking = false;
			}
			
			glfwSwapBuffers(window); // swap the color buffers
		}
	}

	public static void main(String[] args) throws IOException {
		new Game().run();
	}
	
	public void updateCapturePoints() {
		for (int i = 0; i < mapWidth; i++) {
			for (int j = 0; j < mapHeight; j++) {
				if(tiles[i][j].isCapturePoint()) {
					checkCapturePoint(i, j);
				}
			}
		}
	}
	
	public void checkCapturePoint(int tileX, int tileY) {
		int capturingColor = 0;
		for (int i = 0; i < units.size(); i++) {
			int[] position = currentTile(units.get(i).getX(), units.get(i).getY());
			if(tileX == position[0] && tileY == position[1]){
				if(units.get(i).getColor() + 4 == tiles[tileX][tileY].getId()) {
					return;
				}
				else {
					capturingColor = units.get(i).getColor();
				}
			}
		}
		//1 for red, 2 for blue
		if(capturingColor != 0) {
			if(tiles[tileX][tileY].getId() == 5) {
				red_flags--;
			}
			else if(tiles[tileX][tileY].getId() == 6) {
				blue_flags--;
			}
			if(capturingColor == 1) {
				red_flags++;
			}
			else if(capturingColor == 2) {
				blue_flags++;
			}
			tiles[tileX][tileY].setId(capturingColor + 4);
			map[tileX][tileY] = capturingColor + 4;
			server.sendToAllTCP(new TileInfo(tileX, tileY, capturingColor + 4));
			
		}
	}
	
	public void fireProjectile(ArrayList<Integer> firingUnits) {
		if(firingUnits.isEmpty()) {
			return;
		}
		for (Unit u : units) {
			for (int i = 0; i < firingUnits.size(); i++) {
				if(firingUnits.get(i) == u.getId() && u.getCurrentCooldown() == 0){
					projectiles.add(new Projectile(u, u.getTeam(), u.getX(), u.getY(), u.getAngle(), 6, 1, 40, 20));
					u.triggerCooldown();
				}
			}
		}
	}
	
	public void generateMap(){
		mapWidth = 10;
		mapHeight = 10;
		map = new int[mapWidth][mapHeight];
		for (int i = 0; i < mapWidth; i++) {
			for (int j = 0; j < mapHeight; j++) {
				map[i][j] = random.nextInt(4) + 1;
			}
		}
		loadMap();
	}
	
	public void loadMap(){
		tiles = new Tile[mapWidth][mapHeight];
		for (int x = 0; x < mapWidth; x++) {
			for (int y = 0; y < mapHeight; y++) {
				tiles[x][y] = new Tile(map[x][y], x, y);
			}
		}
	}
	
	public void moveOrder(double xpos, double ypos, ArrayList<Integer> selected){
		ArrayList<Unit> movedUnits = new ArrayList<>();
		//get selected units from their id's
		for (int i = 0; i < selected.size(); i++) {
			for (int u = 0; u < units.size(); u++) {
				if(selected.get(i) == units.get(u).getId()){
					movedUnits.add(units.get(u));
					break;
				}
			}
		}
		//move all the selected units
		for (int i = 0; i < movedUnits.size(); i++) {
			moveUnit(movedUnits.get(i), xpos, ypos);
		}
	}
	
	//finds a path between two tiles using dijkstra's algorithm
	public ArrayList<Point> findPath(int startx, int starty, int endx, int endy){
		int[][] distances = new int[mapWidth][mapHeight];
		boolean[][] visited = new boolean[mapWidth][mapHeight];
		Point[][] previousTile = new Point[mapWidth][mapHeight];
		for (int i = 0; i < mapWidth; i++) {
			for (int j = 0; j < mapHeight; j++) {
				distances[i][j] = -1;
				visited[i][j] = false;
			}
		}
		//set distance of starting tile to 0
		distances[startx][starty] = 0;
		previousTile[startx][starty] = null;
		//loop while target is unvisited
		while(visited[endx][endy] == false){
			//get x and y id of the tile that has the smallest distance value (-1 means no data)
			int minDistX = -1;
			int minDistY = -1;
			int minDistance = -1;
			for (int i = 0; i < mapWidth; i++) {
				for (int j = 0; j < mapHeight; j++) {
					if(visited[i][j] == false && distances[i][j] != -1 && (minDistance == -1 || minDistance > distances[i][j])){
						minDistX = i;
						minDistY = j;
						minDistance = distances[i][j];
					}
				}
			}
			if(minDistX == -1){
				System.out.println("No Path Found: " + startx + " " + starty + " to " + endx + " " + endy);
				return null;
			}
			//remove last distance tile from the unvisited list
			visited[minDistX][minDistY] = true;
			int currentDistance = distances[minDistX][minDistY] + tiles[minDistX][minDistY].getMovement();
			//update top left tile distance
			if(minDistX > 0 && minDistY > 0){
				if((distances[minDistX - 1][minDistY - 1] == -1) || (distances[minDistX - 1][minDistY - 1] > currentDistance + 1)){
					distances[minDistX - 1][minDistY - 1] = currentDistance + 1;
					previousTile[minDistX - 1][minDistY - 1] = new Point(minDistX, minDistY);
				}
			}
			//update top tile distance
			if(minDistY > 0){
				if((distances[minDistX][minDistY - 1] == -1) || (distances[minDistX][minDistY - 1] > currentDistance)){
					distances[minDistX][minDistY - 1] = currentDistance;
					previousTile[minDistX][minDistY - 1] = new Point(minDistX, minDistY);
				}
			}
			//update top right tile distance
			if(minDistX < mapWidth - 1 && minDistY > 0){
				if((distances[minDistX + 1][minDistY - 1] == -1) || (distances[minDistX + 1][minDistY - 1] > currentDistance + 1)){
					distances[minDistX + 1][minDistY - 1] = currentDistance + 1;
					previousTile[minDistX + 1][minDistY - 1] = new Point(minDistX, minDistY);
				}
			}
			//update left tile distance
			if(minDistX > 0){
				if((distances[minDistX - 1][minDistY] == -1) || (distances[minDistX - 1][minDistY] > currentDistance)){
					distances[minDistX - 1][minDistY] = currentDistance;
					previousTile[minDistX - 1][minDistY] = new Point(minDistX, minDistY);
				}
			}
			//update right tile distance
			if(minDistX < mapWidth - 1){
				if((distances[minDistX + 1][minDistY] == -1) || (distances[minDistX + 1][minDistY] > currentDistance)){
					distances[minDistX + 1][minDistY] = currentDistance;
					previousTile[minDistX + 1][minDistY] = new Point(minDistX, minDistY);
				}
			}
			//update bottom left tile distance
			if(minDistX > 0 && minDistY < mapHeight - 1){
				if((distances[minDistX - 1][minDistY + 1] == -1) || (distances[minDistX - 1][minDistY + 1] > currentDistance + 1)){
					distances[minDistX - 1][minDistY + 1] = currentDistance + 1;
					previousTile[minDistX - 1][minDistY + 1] = new Point(minDistX, minDistY);
				}
			}
			//update bottom tile distance
			if(minDistY < mapHeight - 1){
				if((distances[minDistX][minDistY + 1] == -1) || (distances[minDistX][minDistY + 1] > currentDistance)){
					distances[minDistX][minDistY + 1] = currentDistance;
					previousTile[minDistX][minDistY + 1] = new Point(minDistX, minDistY);
				}
			}
			//update bottom right tile distance
			if(minDistX < mapWidth - 1 && minDistY < mapHeight - 1){
				if((distances[minDistX + 1][minDistY + 1] == -1) || (distances[minDistX + 1][minDistY + 1] > currentDistance + 1)){
					distances[minDistX + 1][minDistY + 1] = currentDistance + 1;
					previousTile[minDistX + 1][minDistY + 1] = new Point(minDistX, minDistY);
				}
			}
		}
		ArrayList<Point> path = new ArrayList<>();
		Point backTrace = new Point(endx, endy);
		while(backTrace != null){
			path.add(backTrace);
			backTrace = previousTile[(int) (backTrace.X())][(int) (backTrace.Y())];
		}
		Collections.reverse(path);
		//set points in path to pixel coordinate grid from tile coordinates
		for (int i = 0; i < path.size(); i++) {
			Point current = path.get(i);
			current.setX(current.X()*Tile.getWidth()+Tile.getWidth()/2d);
			current.setY(current.Y()*Tile.getHeight()+Tile.getHeight()/2d);
		}
		path.remove(0);
		return path;
	}
	
	//gives the unit an arraylist of points to move to target destination
	public void moveUnit(Unit u, double x, double y){
		int[] startTile = currentTile(u.getX(), u.getY());
		int[] endTile = currentTile(x, y);
		if(startTile[0] == -1 || startTile[1] == -1 || endTile[0] == -1 || endTile[1] == -1){
			return;
		}
		u.setLocationTarget(findPath(startTile[0], startTile[1], endTile[0], endTile[1]));
	}
	
	//gets the tile a coordinate is in
	public int[] currentTile(double x, double y){
		int[] tilePos = {0,0};
		int tileWidth = Tile.getWidth();
		int tileHeight = Tile.getHeight();
		int xlower = 0;
		int xupper = tileWidth;
		//find tile x position
		while(!(xlower <= x && x < xupper)){
			xlower += tileWidth;
			xupper += tileWidth;
			tilePos[0]++;
			//if x coordinate is out of bounds
			if(tilePos[0] >= mapWidth){
				return new int[]{-1,-1};
			}
		}
		int ylower = 0;
		int yupper = tileHeight;
		//find tile y position
		while(!(ylower <= y && y < yupper)){
			ylower += tileHeight;
			yupper += tileHeight;
			tilePos[1]++;
			//if y coordinate is out of bounds
			if(tilePos[1] >= mapHeight){
				return new int[]{-1,-1};
			}
		}
		return tilePos;
	}
	
	//check projectile collisions
	public void checkProjectiles() {
		for (int i = 0; i < projectiles.size(); i++) {
    		Projectile p = projectiles.get(i);
			for (int j = 0; j < units.size(); j++) {
				Unit u = units.get(j);
				if(gamelogic.polygon_intersection(p.getPoints(), u.getPoints()) && !(p.getTeam().equals(u.getTeam()))){
					u.setHealth(u.getHealth() - p.getDamage());
					//update score for kill
					if(u.getHealth() <= 0) {
						if(p.getTeam().equals("red")) {
							red_score += 100;
						}
						else if(p.getTeam().equals("blue")) {
							blue_score += 100;
						}
					}
					projectiles.remove(p);
					i--;
					break;
				}
			}
		}
	}
	
	public void respawnUnits() {
		for(Unit u : units) {
			if(u.getHealth() <= 0) {
				u.setPosition(100*(random.nextInt(6) + 1), 100*(random.nextInt(6) + 1), random.nextInt(360));
				u.setHealth(u.getMaxHealth());
			}
		}
	}
	
	public Unit createUnit(int newownerid, String newteam, double newx, double newy, double newangle, int newcolor){
		Unit u = new Unit(unitId, newownerid, newteam, newx, newy, newangle, newcolor);
		unitId++;
		return u;
	}
	
	public void projectRelativeCameraCoordinates(){
		glMatrixMode(GL_PROJECTION);
        glLoadIdentity(); // Resets any previous projection matrices
        glOrtho((-windowXOffset * getWidthScalar()) + viewX, viewX + cameraWidth + (windowXOffset * getWidthScalar()), viewY + cameraHeight + ((windowYOffset)* getHeightScalar()), viewY + ((-windowYOffset) * getHeightScalar()), 1, -1);
        glMatrixMode(GL_MODELVIEW);
	}
	
	public void projectTrueWindowCoordinates(){
		glMatrixMode(GL_PROJECTION);
        glLoadIdentity(); // Resets any previous projection matrices
        glOrtho(-windowXOffset, WINDOW_WIDTH + windowXOffset, WINDOW_HEIGHT + windowYOffset, -windowYOffset, 1, -1);
        glMatrixMode(GL_MODELVIEW);
	}
	
	public double getWidthScalar(){
		return(double) cameraWidth / (double) WINDOW_WIDTH;
	}
	
	public double getHeightScalar(){
		return(double) cameraHeight / (double) WINDOW_HEIGHT;
	}
	
	public void updateZoomLevel(boolean zoomOut){
		DoubleBuffer xpos = BufferUtils.createDoubleBuffer(3);
		DoubleBuffer ypos = BufferUtils.createDoubleBuffer(3);
		glfwGetCursorPos(window, xpos, ypos);
		//convert the glfw coordinate to our coordinate system
		xpos.put(0, Math.min(Math.max(xpos.get(0), windowXOffset), WINDOW_WIDTH + windowXOffset));
		ypos.put(0, Math.min(Math.max(ypos.get(0), windowYOffset), WINDOW_HEIGHT + windowYOffset));
		//relative camera coordinates
		xpos.put(1, getWidthScalar() * (xpos.get(0) - windowXOffset) + viewX);
		ypos.put(1, getHeightScalar() * (ypos.get(0) - windowYOffset) + viewY);
		//true window coordinates
		xpos.put(2, xpos.get(0) - windowXOffset);
		ypos.put(2, ypos.get(0) - windowYOffset);
		
		boolean mouseInFrame = false;
		double oldX = xpos.get(1);
		double oldY = ypos.get(1);
		double xAxisDistance = 0;
		double yAxisDistance = 0;
		
		if(xpos.get(2) > 0 && xpos.get(2) < gameScreenWidth && ypos.get(2) > 0 && ypos.get(2) < gameScreenHeight){
			mouseInFrame = true;
			xAxisDistance = xpos.get(2)/WINDOW_WIDTH;
			yAxisDistance = ypos.get(2)/WINDOW_HEIGHT;
		}
		
		int MIN_WIDTH = 100;
		int MIN_HEIGHT = 100;
		int MAX_WIDTH = 1280;
		int MAX_HEIGHT = 1280;
		
		double zoomLevel = 4d/3d;
		
		if(zoomOut){
			if(cameraWidth * zoomLevel <= MAX_WIDTH && cameraHeight * zoomLevel <= MAX_HEIGHT){
				cameraWidth *= zoomLevel;
				cameraHeight *= zoomLevel;
				if(mouseInFrame){
					viewX = oldX - cameraWidth * xAxisDistance;
					viewY = oldY - cameraHeight * yAxisDistance;
				}
				else{
					viewX -= cameraWidth / 6;
					viewY -= cameraWidth / 6;
				}
				if(viewX + cameraWidth > gameScreenWidth){
					viewX = gameScreenWidth - cameraWidth;
				}
				if(viewY + cameraHeight > gameScreenHeight){
					viewY = gameScreenHeight - cameraHeight;
				}
				if(viewX < 0){
					viewX = 0;
				}
				if(viewY < 0){
					viewY = 0;
				}
			}
		}
		else{
			if(cameraWidth / zoomLevel >= MIN_WIDTH && cameraHeight / zoomLevel >= MIN_HEIGHT){
				cameraWidth /= zoomLevel;
				cameraHeight /= zoomLevel;
				if(mouseInFrame){
					viewX = oldX - cameraWidth * xAxisDistance;
					viewY = oldY - cameraHeight * yAxisDistance;
				}
				else{
					viewX += cameraWidth / 4;
					viewY += cameraHeight / 4;
				}
			}
		}
	}

}

	
	

