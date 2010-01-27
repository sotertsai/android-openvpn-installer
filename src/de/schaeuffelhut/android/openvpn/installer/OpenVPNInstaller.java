package de.schaeuffelhut.android.openvpn.installer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import de.schaeuffelhut.android.openvpn.util.LoggerThread;
import de.schaeuffelhut.android.openvpn.util.Util;

public class OpenVPNInstaller extends Activity {

	final static String TAG = "OpenVPN-Installer";

	final static int DIALOG_BACKUP = 1;
	final static int DIALOG_CHOOSE_TARGET = 2;
	final static int DIALOG_CONFIRM_INSTALL = 3;
	final static int DIALOG_LOG = 4;
	final static int DIALOG_HELP = 5;

	private ImageView mIcon;
	private TextView mMsg;
	private TextView mPath;
//	private Button mBackup;
	private Button mInstall;
	private Button mExit;

	private FindBinaryThread mFindBinaryThread;
	private InstallerThread mInstallerThread;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		mIcon = (ImageView)findViewById(R.id.installer_installed_icon);
		mMsg = (TextView)findViewById(R.id.installer_binary_msg);
		mPath = (TextView)findViewById(R.id.installer_binary_path);

//		mBackup = (Button)findViewById( R.id.installer_backup );
//		mBackup.setOnClickListener(new View.OnClickListener() {
//			public void onClick(View v) {
//				backup();
//			}
//		});

		mInstall = (Button)findViewById( R.id.installer_install );
		mInstall.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				showDialog( DIALOG_CONFIRM_INSTALL );
			}
		});

		mExit = (Button)findViewById( R.id.installer_exit );
		mExit.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				finish();
			}
		});

		registerForContextMenu( findViewById( R.id.installer ) );
		
		findOpenVpnBinary();
	}

	
	/*
	 * Dialogs
	 */
	
	@Override
	protected Dialog onCreateDialog(int id) {
		switch (id) {

		case DIALOG_BACKUP:
			return null;

		case DIALOG_CHOOSE_TARGET: {
			final File xbin = new File("/system/xbin/openvpn");
			final File bin = new File("/system/bin/openvpn");
			return new AlertDialog.Builder(this)
			.setTitle( "Choose target directory" )
			.setItems(new String[]{ xbin.getParent(), bin.getParent()}, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
					switch (which) {
					case 0:
						install( xbin );
						break;
					case 1:
						install( bin );
						break;
					}
				}
			})
			.setNegativeButton( "Cancel", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			})
			.create(); }
			

		case DIALOG_CONFIRM_INSTALL:
			return new AlertDialog.Builder(this)
			.setTitle( "Install OpenVPN binary" )
			.setMessage(
					"You are about to install the openvpn binary on your phone. " +
					"You get to chooese the target directory in the next screen." +
					"Existing files will be overwritten." +
					"The install process may or may not succeed on your phone. " +
					"The binary may or may not work on your phone. " +
					"You need root! " +
					"You still need to make sure you have the 'tun' capability for OpenVPN to work! " +
					"PROCEED AT YOUR OWN RISK"
			)
			.setIcon( android.R.drawable.ic_dialog_alert )
			.setPositiveButton( "Install", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
					//					showDialog( DIALOG_INSTALL );
//					install();
					showDialog( DIALOG_CHOOSE_TARGET );
				}
			})
			.setNegativeButton( "Cancel", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			})
			.create();

		case DIALOG_LOG: {
			TextView logView = new TextView(this);
			logView.setId( 815 );
//			logView.setLayoutParams( new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT, 1) );
			
			ScrollView scrollView = new ScrollView(this);
			scrollView.addView(logView);
			
			return new AlertDialog.Builder(this)
			.setTitle( "Log" )
			.setIcon( android.R.drawable.ic_dialog_info )
			.setView( scrollView )
			.setNeutralButton( "Dismiss", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					dialog.dismiss();
				}
			})
			.create();
		}

		case DIALOG_HELP:
			return HelpDialog.makeDialog(this);
		
		default:
			throw new UnexpectedSwitchValueException(id);
		}
	}

	@Override
	protected void onPrepareDialog(int id, Dialog dialog) {
		switch (id) {
		case DIALOG_LOG: {
			TextView logView = (TextView)dialog.findViewById( 815 );
			logView.setText("");
			for(String s : mInstallerThread.log ){
				if ( logView.length() > 0 )
					logView.append( "\n" );
				logView.append( s );
			}
			if ( logView.length() == 0 )
				logView.append( "The log is empty." );
		} break;
		}
	}

	/*
	 * Menus 
	 */
	
	final static int OPTIONS_MENU_SHOW_LOG = 1;
	final static int OPTIONS_MENU_HELP = 2;
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, OPTIONS_MENU_SHOW_LOG, 0, "Show Log");
		menu.add(0, OPTIONS_MENU_HELP, 0,  "Help");
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		super.onPrepareOptionsMenu(menu);
		menu.findItem( OPTIONS_MENU_SHOW_LOG ).setEnabled( mInstallerThread != null );
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
	    case OPTIONS_MENU_SHOW_LOG:
	    	showDialog( DIALOG_LOG );
	        return true;
	    case OPTIONS_MENU_HELP:
	    	showDialog( DIALOG_HELP );
	        return true;
	    }
	    return false;
	}
	
	/*
	 * Handler
	 */
	
	final static int HANDLER_BINARY_LOCATED = 1;
	final static int HANDLER_FIND_BINARY = 2;
	final static int HANDLER_INSTALLER_FINISHED = 3;

	final Handler handler = new Handler() {

		public void handleMessage(Message msg) {
			switch( msg.what ){
			case HANDLER_BINARY_LOCATED: {
				String[] paths = msg.getData().getStringArray("paths");
				if ( paths.length == 0 ){
					setOpenVpnPath( null );
				} else {
					StringBuilder sb = new StringBuilder();
					for(String path : paths){
						if ( sb.length() > 0 )
							sb.append('\n');
						sb.append( path );
					}
					setOpenVpnPath( sb.toString() );
				}
			} break;

			case HANDLER_FIND_BINARY:
				findOpenVpnBinary();
				break;

			case HANDLER_INSTALLER_FINISHED:
				findOpenVpnBinary();
				break;
			}

		}
	};



	private final class InstallerThread extends Thread {
		private final ProgressDialog progressDialog;
		final File file;
		ArrayList<String> log = new ArrayList<String>();

		private InstallerThread(ProgressDialog progressDialog, File target) {
			this.progressDialog = progressDialog;
			this.file = target;
		}

		@Override
		public void run() {
			try {Thread.sleep(250);} catch (InterruptedException e) {}
			try
			{

				Mount mountPoint = findMountPointRecursive(file);
				log( "mountPoint " + mountPoint );

				final boolean isReadOnly = mountPoint.flags.contains( "ro");

				if ( isReadOnly ) {
					log( String.format( "%s is mounted read-only", mountPoint.mountPoint ) );
					log( String.format( "trying to remount read-write" ) );
					exec( "su", "-c", String.format( "mount -oremount,rw %s", mountPoint.mountPoint.getAbsolutePath() ) );
					mountPoint = findMountPointRecursive(file);
				} else {
					log( String.format( "%s is already mounted read-write", mountPoint.mountPoint ) );
				}

				if ( mountPoint.flags.contains( "rw") ) {
					log( String.format( "copying openvpn to %s", file ) );
					File tmp = unpackAsset();
					exec( "su", "-c", String.format( "cp '%s' '%s'; chmod 555 '%s'", 
							tmp.getAbsolutePath().replace( "\\", "\\\\").replace("'", "\\'"),
							file.getAbsolutePath().replace( "\\", "\\\\").replace("'", "\\'"),
							file.getAbsolutePath().replace( "\\", "\\\\").replace("'", "\\'")
					));
					tmp.delete();

					log( String.format( "making binary executable", file ) );
					exec( "su", "-c", String.format( "chmod 555 '%s'", 
							file.getAbsolutePath().replace( "\\", "\\\\").replace("'", "\\'")
					));

					if ( isReadOnly ) {
						log( String.format( "%s was mounted read-read-only", mountPoint.mountPoint ) );
						log( String.format( "trying to remount read-only" ) );
						exec( "su", "-c", String.format( "mount -oremount,ro %s", mountPoint.mountPoint.getAbsolutePath() ) );
						mountPoint = findMountPointRecursive(file);
						if ( mountPoint.flags.contains("ro") )
							log( String.format( "Success!" ) );
						else
							log( String.format( "Failed to restore read-only mount, reboot phone to fix it!" ) );
					}
				} else {
					log( String.format( "%s is still mounted read-only", mountPoint.mountPoint ) );
					log( String.format( "Aborting!", mountPoint.mountPoint ) );
				}
			} catch (Exception e) {
				log( e.getMessage() );
			}
			finally
			{
				progressDialog.dismiss();
				handler.sendEmptyMessage( HANDLER_INSTALLER_FINISHED );
			}
		}

		private int exec(String... strings) {
			try {
				log( String.format("executing '%s'", Util.join( Arrays.asList( strings ) , ' ' ) ) ) ;

				Process process = Runtime.getRuntime().exec( strings );
				LoggerThread stdout = new LoggerThread(TAG, process.getInputStream(), true){
					@Override
					protected void onLogLine(String line) {
						log( "STDOUT: " + line ) ;
					}
				};
				LoggerThread stderr = new LoggerThread(TAG, process.getErrorStream(), true){
					@Override
					protected void onLogLine(String line) {
						log( "STDERR: " + line ) ;
					}
				};
				stdout.start();
				stderr.start();
				int waitFor = process.waitFor();
				log( String.format("exit code %d", waitFor ) ) ;
				return waitFor;
			} catch (InterruptedException e) {
				throw new RuntimeException( e );
			} catch (IOException e) {
				throw new RuntimeException( e );
			}
		}

		private void log(String msg)
		{
			Log.d( TAG, msg );
			log.add( msg );
		}
	}


	private class FindBinaryThread extends Thread {
		final ProgressDialog mProgressDialog;
		final Handler mHandler;

		public FindBinaryThread(ProgressDialog progressDialog, Handler handler) {
			mProgressDialog = progressDialog;
			mHandler = handler;
		}

		public void run()
		{
			try {Thread.sleep(1000);} catch (InterruptedException e) {}

			FilenameFilter filenameFilter = new FilenameFilter() {
				public boolean accept(File dir, String filename) {
					return "openvpn".equals( filename ) || "ovpn".equals( filename );
				}
			};

			ArrayList<File> paths = new ArrayList<File>();
			paths.addAll( Arrays.asList( new File("/system/xbin").listFiles( filenameFilter) ) );
			paths.addAll( Arrays.asList( new File("/system/bin").listFiles( filenameFilter) ) );

			String[] pathNames = new String[paths.size()];
			for(int i=0; i<paths.size(); i++ )
				pathNames[i] = paths.get(i).getAbsolutePath();

			Message msg = mHandler.obtainMessage( HANDLER_BINARY_LOCATED );
			Bundle b = new Bundle();
			b.putString("path", (paths.isEmpty() ? null : paths.get(0).getAbsolutePath() ) );
			b.putStringArray("paths", pathNames );
			msg.setData(b);
			mHandler.sendMessage(msg);

			mProgressDialog.dismiss();
		}

	}

	void setSearching()
	{
		mIcon.setImageResource( R.drawable.ic_scanning );
		mMsg.setText( "Searching..." );
		mPath.setText( "" );
	}

	void setInstalling()
	{
		mIcon.setImageResource( R.drawable.ic_scanning );
		mMsg.setText( "Searching..." );
		mPath.setText( "" );
	}

	void setOpenVpnPath(String pathName) {

		if ( pathName == null )
		{
			mIcon.setImageResource( R.drawable.ic_not_installed );
			mMsg.setText( "Binary not found" );
			mPath.setText( mInstallerThread == null ? "" : "Choose 'Show Log' from menu!" );
//			mBackup.setEnabled(false);
		}
		else
		{
			mIcon.setImageResource( R.drawable.ic_installed );
			mMsg.setText( "Binary installed" );
			mPath.setText( pathName );
//			mBackup.setEnabled(true);
		}
	}

	/*
	 * logic and callbacks
	 */

	void findOpenVpnBinary() {
		//		showDialog( DIALOG_LOCATING_BINARY );
		ProgressDialog progressDialog = new ProgressDialog(this);
		progressDialog.setMessage("Locating openvpn binary");
		progressDialog.show();
		setSearching();
		mFindBinaryThread = new FindBinaryThread( progressDialog, handler );
		mFindBinaryThread.start();
	}

	void backup() {
	}

	void install(File target) {

		final ProgressDialog progressDialog = new ProgressDialog(this);
		progressDialog.setMessage("Installing openvpn binary");
		progressDialog.show();
		setInstalling();

		mInstallerThread = new InstallerThread(progressDialog, target);
		mInstallerThread.start();
	}


	static class Mount {
		final File device;
		final File mountPoint;
		final String type;
		final Set<String> flags;

		public Mount(File device, File path, String type, String flagsStr) {
			this.device = device;
			this.mountPoint = path;
			this.type = type;
			this.flags = new HashSet<String>( Arrays.asList(flagsStr.split(",")));
		}

		@Override
		public String toString() {
			return String.format( "%s on %s type %s %s", device, mountPoint, type, flags );
		}
	}

	private Mount findMountPointRecursive(File file)
	{
		try
		{
			ArrayList<Mount> mounts = getMounts();
			for( File path = file; path != null; path = path.getParentFile() )
				for(Mount mount : mounts )
					if ( mount.mountPoint.equals( path ) )
						return mount;
			return null;
		}
		catch (IOException e) {
			throw new RuntimeException( e );
		}
	}

	private ArrayList<Mount> getMounts() throws FileNotFoundException, IOException {
		LineNumberReader lnr = null;
		try
		{
			lnr = new LineNumberReader( new FileReader( "/proc/mounts" ) );
			String line;
			ArrayList<Mount> mounts = new ArrayList<Mount>();
			while( (line = lnr.readLine()) != null ){
				String[] fields = line.split(" ");
				mounts.add( new Mount(
						new File(fields[0]), // device
						new File(fields[1]), // mountPoint
						fields[2], // fstype
						fields[3] // flags
				) );
			}
			return mounts;
		}
		finally
		{
			Util.closeQuietly( lnr );
		}
	}


	private File unpackAsset(){
		InputStream asset = null;
		OutputStream os = null;
		try {
			File tmp = File.createTempFile("openvpn", "tmp");
			try{
				asset = getAssets().open("openvpn");
				os = new FileOutputStream(tmp);
				byte[] buf = new byte[1024];
				int length;
				while( ( length = asset.read(buf) ) >= 0 )
					os.write(buf, 0, length);
				return tmp;
			}finally{
				Util.closeQuietly(asset);
				Util.closeQuietly(os);
			}
		} catch (IOException e) {
			throw new RuntimeException( e );
		}
	}
}
