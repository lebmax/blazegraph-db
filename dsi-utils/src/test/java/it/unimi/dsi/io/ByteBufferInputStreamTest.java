package it.unimi.dsi.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.StandardOpenOption;
import java.util.Random;

import junit.framework.TestCase;

/** Note: this test has little meaning unless you change ByteBufferInputStream.FIRST_SHIFT to 16.
 */

public class ByteBufferInputStreamTest extends TestCase {

	private static final boolean DEBUG = false;

	public void testStream() throws IOException {
		final int l = 100000;
		long seed = System.currentTimeMillis();
		if ( DEBUG ) System.err.println( "Seed: " + seed );
		Random random = new Random( seed );

		for( int n = 1; n < 4; n++ ) {
			// Closing the channel does not release a mapping. Use a fresh file for each size.
			final File f = File.createTempFile( ByteBufferInputStreamTest.class.getName(), "tmp" );
			f.deleteOnExit();

			try ( FileOutputStream fos = new FileOutputStream( f ) ) {
				for( int i = 0; i < l * n; i++ ) fos.write( random.nextInt() & 0xFF );
			}

			try ( FileChannel fc = FileChannel.open( f.toPath(), StandardOpenOption.READ,
					StandardOpenOption.DELETE_ON_CLOSE ) ) {
				final InputStream test = Channels.newInputStream( fc );
				final ByteBufferInputStream bis = ByteBufferInputStream.map( fc, MapMode.READ_ONLY );
				int a1, a2, off, len, pos;
				byte b1[] = new byte[ 32768 ];
				byte b2[] = new byte[ 32768 ];

				for( int k = 0; k < l / 10; k++ ) {

					switch ( random.nextInt( 6 ) ) {

					case 0:
						if ( DEBUG ) System.err.println( "read()" );
						a1 = bis.read();
						a2 = test.read();
						assertEquals( a2, a1 );
						break;

					case 1:
						off = random.nextInt( b1.length );
						len = random.nextInt( b1.length - off + 1 );
						a1 = bis.read( b1, off, len );
						a2 = test.read( b2, off, len );
						if ( DEBUG ) System.err.println( "read(b, " + off + ", " + len + ")" );

						assertEquals( a2, a1 );

						for ( int i = off; i < off + len; i++ )
							assertEquals( b2[ i ], b1[ i ] );
						break;

					case 2:
						if ( DEBUG ) System.err.println( "available()" );
						assertEquals( test.available(), bis.available() );
						break;

					case 3:
						pos = (int)bis.position();
						if ( DEBUG ) System.err.println( "position()=" + pos );
						assertEquals( (int)fc.position(), pos );
						break;

					case 4:
						pos = random.nextInt( l * n );
						bis.position( pos );
						if ( DEBUG ) System.err.println( "position(" + pos + ")" );
						fc.position( pos );
						break;

					case 5:
						pos = random.nextInt( (int)( l * n - bis.position() + 1 ) );
						if ( DEBUG ) System.err.println( "skip(" + pos + ")" );
						a1 = (int)bis.skip( pos );
						a2 = (int)test.skip( pos );
						assertEquals( a2, a1 );
						break;
					}
				}
			}
		}
	}
}
