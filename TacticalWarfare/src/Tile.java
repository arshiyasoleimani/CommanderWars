
public class Tile {
	
	static int tileWidth = 64;
	static int tileHeight = 64;
	
	static Texture tex1 = new Texture("mountain.png");
	static Texture tex2 = new Texture("grass.png");
	static Texture tex3 = new Texture("forest.png");
	
	double[] vertices = new double[8];
	
	int id;
	int x_pos;
	int y_pos;
	
	public Tile(int newid, int newx, int newy){
		id = newid;
		x_pos = newx;
		y_pos = newy;
		setVertices();
	}
	
	public void setVertices(){
		vertices[0] = tileWidth * x_pos;
		vertices[1] = tileHeight * y_pos;
		vertices[2] = tileWidth * x_pos;
		vertices[3] = tileHeight * y_pos + tileHeight;
		vertices[4] = tileWidth * x_pos + tileWidth;
		vertices[5] = tileHeight * y_pos;
		vertices[6] = tileWidth * x_pos + tileWidth;
		vertices[7] = tileHeight * y_pos + tileHeight;
	}
	
	public void setTexture(){
		switch (id) {
		case 1:
			tex1.bind();
			break;
		case 2:
			tex2.bind();
			break;
		case 3:
			tex3.bind();
			break;
		}
	}
	
	public int getMovement(){
		switch (id) {
		case 1:
			return 20;
		case 2:
			return 2;
		case 3:
			return 4;
		default:
			return -1;
		}
	}
	
	public static int getWidth(){
		return tileWidth;
	}
	
	public static int getHeight(){
		return tileHeight;
	}
	
	public int getId(){
		return id;
	}
	
	public double[] getVertices(){
		return vertices;
	}
	
}
