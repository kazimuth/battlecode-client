package battlecode.client.viewer.render;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.util.Random;

import battlecode.common.MapLocation;

// Basically copied from EnergonTransferAnimation
// (why the hell is that still called that, anyway??)
public class MiningAnim extends Animation {
	
	// how long we want MiningAnims to last
	private final static int LIFETIME = 10;
	
	private final Rectangle2D.Float shape;
	private float xvel;
	private float yvel;
	
	public MiningAnim(final MapLocation loc, final float oreAmount, final int turn) {
		super(LIFETIME);

		final float width = (float)Math.sqrt(oreAmount) / 4.0f;
		final float cornerX = loc.x + 0.5f - width/2.0f;
		final float cornerY = loc.y + 0.5f - width/2.0f;
		shape = new Rectangle2D.Float(cornerX, cornerY, width, width);
		
		// Deterministic random direction based on parameters
		final Random random = new Random((long)(loc.x*loc.y*oreAmount*turn));
		xvel = (float)random.nextGaussian() / (LIFETIME/2);
		yvel = (float)random.nextGaussian() / (LIFETIME/2);
	}
	
	public MiningAnim(final Rectangle2D.Float shape, final int curFrame, final float xvel, final float yvel) {
		super(LIFETIME);
		this.shape = shape;
		this.curFrame = curFrame;
		this.xvel = xvel;
		this.yvel = yvel;
	}
	
	@Override
	public void draw(Graphics2D g2) {
		// move shape a bit
		shape.x += xvel;
		shape.y += yvel;
		xvel *= 0.9;
		yvel *= 0.9;
		// fade over time:
		final float alpha = 0.75f * ((float) maxFrame - (float)curFrame) / (float) maxFrame;
		if (alpha < 0 || alpha > 1) return;
		
		// See DrawState::draw - lum is always .75f currently
		g2.setColor(new Color(.75f, .75f, .75f, alpha));
		g2.fill(shape);
	}

	@Override
	public Object clone() {
		return new MiningAnim(this.shape, this.curFrame, this.xvel, this.yvel);
	}

}
