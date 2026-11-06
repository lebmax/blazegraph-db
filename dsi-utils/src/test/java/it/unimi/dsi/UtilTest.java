package it.unimi.dsi;

import it.unimi.dsi.Util;
import junit.framework.TestCase;

public class UtilTest extends TestCase {
	
	public void testFormatBinarySize() {
		assertEquals( "1", Util.formatBinarySize( 1 ) );
		assertEquals( "2", Util.formatBinarySize( 2 ) );
		boolean ok = false;
		try {
			Util.formatBinarySize( 6 );
		}
		catch( IllegalArgumentException e ) {
			ok = true;
		}
		assertTrue( ok );
		assertEquals( "128", Util.formatBinarySize( 128 ) );
		assertEquals( "1Ki", Util.formatBinarySize( 1024 ) );
		assertEquals( "2Ki", Util.formatBinarySize( 2048 ) );
		assertEquals( "1Mi", Util.formatBinarySize( 1024 * 1024 ) );
		assertEquals( "2Mi", Util.formatBinarySize( 2 * 1024 * 1024 ) );
		assertEquals( "1Gi", Util.formatBinarySize( 1024 * 1024 * 1024 ) );
		assertEquals( "2Gi", Util.formatBinarySize( 2L * 1024 * 1024 * 1024 ) );
		assertEquals( "1Ti", Util.formatBinarySize( 1024L * 1024 * 1024 * 1024 ) );
		assertEquals( "2Ti", Util.formatBinarySize( 2L * 1024 * 1024 * 1024 * 1024 ) );
	}

	public void testFormatSize() {
		assertEquals( "1", Util.formatSize( 1 ) );
		assertEquals( "2", Util.formatSize( 2 ) );
		assertEquals( "128", Util.formatSize( 128 ) );
		assertEquals( String.format( "%.2fK", 1.0 ), Util.formatSize( 1000 ) );
		assertEquals( String.format( "%.2fK", 2.0 ), Util.formatSize( 2000 ) );
		assertEquals( String.format( "%.2fK", 2.5 ), Util.formatSize( 2500 ) );
		assertEquals( String.format( "%.2fM", 1.0 ), Util.formatSize( 1000 * 1000 ) );
		assertEquals( String.format( "%.2fM", 2.0 ), Util.formatSize( 2 * 1000 * 1000 ) );
		assertEquals( String.format( "%.2fG", 1.0 ), Util.formatSize( 1000 * 1000 * 1000 ) );
		assertEquals( String.format( "%.2fG", 2.0 ), Util.formatSize( 2L * 1000 * 1000 * 1000 ) );
		assertEquals( String.format( "%.2fT", 1.0 ), Util.formatSize( 1000L * 1000 * 1000 * 1000 ) );
		assertEquals( String.format( "%.2fT", 2.0 ), Util.formatSize( 2L * 1000 * 1000 * 1000 * 1000 ) );
	}
	

}
