package name.boyle.chris.sgtpuzzles;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import name.boyle.chris.sgtpuzzles.compat.PrefsSaver;
import name.boyle.chris.sgtpuzzles.compat.SysUIVisSetter;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.NavUtils;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarActivity;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

public class GamePlay extends ActionBarActivity implements OnSharedPreferenceChangeListener
{
	static final String TAG = "GamePlay";
	static final String STATE_PREFS_NAME = "state";
	static final String ORIENTATION_KEY = "orientation";
	static final String ARROW_KEYS_KEY = "arrowKeys";
	static final String INERTIA_FORCE_ARROWS_KEY = "inertiaForceArrows";
	private static final String BRIDGES_SHOW_H_KEY = "bridgesShowH";
	private static final String FULLSCREEN_KEY = "fullscreen";
	private static final String STAY_AWAKE_KEY = "stayAwake";
	private static final String UNDO_REDO_KBD_KEY = "undoRedoKbd";
	private static final boolean UNDO_REDO_KBD_DEFAULT = true;
	private static final String PATTERN_SHOW_LENGTHS_KEY = "patternShowLengths";
	private static final String COMPLETED_PROMPT_KEY = "completedPrompt";
	public static final String SAVED_COMPLETED = "savedCompleted";
	public static final String SAVED_GAME = "savedGame";
	public static final String LAST_PARAMS_PREFIX = "last_params_";

	private ProgressDialog progress;
	private TextView statusBar;
	private SmallKeyboard keyboard;
	private RelativeLayout mainLayout;
	private GameView gameView;
	private SubMenu typeMenu;
	private LinkedHashMap<String, String> gameTypes;
	private int currentType = 0;
	private boolean workerRunning = false;
	private Process gameGenProcess = null;
	private boolean solveEnabled = false, customVisible = false,
			undoEnabled = false, redoEnabled = false;
	private SharedPreferences prefs, state;
	private static final int CFG_SETTINGS = 0, CFG_SEED = 1, CFG_DESC = 2,
		C_STRING = 0, C_CHOICES = 1, C_BOOLEAN = 2;
	static final long MAX_SAVE_SIZE = 1000000; // 1MB; we only have 16MB of heap
	private boolean gameWantsTimer = false;
	private static final int TIMER_INTERVAL = 20;
	private StringBuffer savingState;
	private AlertDialog dialog;
	private ArrayList<String> dialogIds;
	private TableLayout dialogLayout;
	String currentBackend = null;
	private Thread worker;
	private String lastKeys = "";
	private static final File storageDir = Environment.getExternalStorageDirectory();
	private String[] games;
	private Menu menu;
	private String maybeUndoRedo = "ur";
	private PrefsSaver prefsSaver;
	private boolean startedFullscreen = false, cachedFullscreen = false;
	private boolean keysAlreadySet = false;
	private boolean everCompleted = false;

	static boolean isAlive;

	enum MsgType { TIMER, DONE, ABORT }
	static class PuzzlesHandler extends Handler
	{
		final WeakReference<GamePlay> ref;
		public PuzzlesHandler(GamePlay outer) {
			ref = new WeakReference<GamePlay>(outer);
		}
		public void handleMessage( Message msg ) {
			GamePlay outer = ref.get();
			if (outer != null) outer.handleMessage(msg);
		}
	}
	final Handler handler = new PuzzlesHandler(this);

	private void handleMessage(Message msg) {
		switch( MsgType.values()[msg.what] ) {
		case TIMER:
			if( progress == null ) timerTick();
			if( gameWantsTimer ) {
				handler.sendMessageDelayed(
						handler.obtainMessage(MsgType.TIMER.ordinal()),
						TIMER_INTERVAL);
			}
			break;
		case DONE:
			resizeEvent(gameView.w, gameView.h);
			dismissProgress();
			if( menu != null ) onPrepareOptionsMenu(menu);
			save();
			break;
		case ABORT:
			stopNative();
			dismissProgress();
			if (msg.obj != null && !msg.obj.equals("")) {
				messageBox(this, getString(R.string.Error), (String)msg.obj);
			} else if (msg.arg1 == 1) {  // see showProgress
				startChooserAndFinish();
			}
			break;
		}
	}

	private void showProgress(int msgId, final boolean returnToChooser)
	{
		progress = new ProgressDialog(this);
		progress.setMessage( getString(msgId) );
		progress.setIndeterminate(true);
		progress.setCancelable(true);
		progress.setCanceledOnTouchOutside(false);
		// returnToChooser sets msg.arg1 to 1
		final Message cancelMessage = handler.obtainMessage(MsgType.ABORT.ordinal(), returnToChooser ? 1 : 0, 0);
		progress.setOnCancelListener(new OnCancelListener() {
			public void onCancel(DialogInterface dialog) {
				cancelMessage.sendToTarget();
			}
		});
		progress.setButton( DialogInterface.BUTTON_NEGATIVE, getString(android.R.string.cancel), cancelMessage);
		progress.show();
	}

	private void dismissProgress()
	{
		if( progress == null ) return;
		try {
			progress.dismiss();
		} catch (IllegalArgumentException ignored) {}  // race condition?
		progress = null;
	}

	String saveToString()
	{
		if (currentBackend == null || progress != null) return null;
		savingState = new StringBuffer();
		serialise();  // serialiseWrite() callbacks will happen in here
		String s = savingState.toString();
		savingState = null;
		return s;
	}

	@SuppressLint("CommitPrefEdits")
	private void save()
	{
		String s = saveToString();
		if (s == null || s.length() == 0) return;
		SharedPreferences.Editor ed = state.edit();
		ed.remove("engineName");
		ed.putString(SAVED_GAME, s);
		ed.putBoolean(SAVED_COMPLETED, everCompleted);
		ed.putString(LAST_PARAMS_PREFIX + games[identifyBackend(s)], getCurrentParams());
		prefsSaver.save(ed);
	}

	void gameViewResized()
	{
		if (progress == null && gameView.w > 10 && gameView.h > 10)
			resizeEvent(gameView.w, gameView.h);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		prefs.registerOnSharedPreferenceChangeListener(this);
		state = getSharedPreferences(STATE_PREFS_NAME, MODE_PRIVATE);
		prefsSaver = PrefsSaver.get(this);
		games = getResources().getStringArray(R.array.games);
		gameTypes = new LinkedHashMap<String, String>();

		applyFullscreen(false);  // must precede super.onCreate and setContentView
		cachedFullscreen = startedFullscreen = prefs.getBoolean(FULLSCREEN_KEY, false);
		applyStayAwake();
		applyOrientation();
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		applyChooserIcon();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			getSupportActionBar().addOnMenuVisibilityListener(new ActionBar.OnMenuVisibilityListener() {
				@Override
				public void onMenuVisibilityChanged(boolean visible) {
					// https://code.google.com/p/android/issues/detail?id=69205
					if (!visible) {
						supportInvalidateOptionsMenu();
						rethinkActionBarCapacity();
					}
				}
			});
		}
		mainLayout = (RelativeLayout)findViewById(R.id.mainLayout);
		statusBar = (TextView)findViewById(R.id.statusBar);
		gameView = (GameView)findViewById(R.id.game);
		keyboard = (SmallKeyboard)findViewById(R.id.keyboard);
		dialogIds = new ArrayList<String>();
		setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);
		gameView.requestFocus();
		onNewIntent(getIntent());
		getWindow().setBackgroundDrawable(null);
		isAlive = true;
	}

	/** work around http://code.google.com/p/android/issues/detail?id=21181 */
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		return progress == null && (gameView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event));
	}

	/** work around http://code.google.com/p/android/issues/detail?id=21181 */
	@Override
	public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
		return progress == null && (gameView.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event));
	}

	@Override
	protected void onNewIntent(Intent intent)
	{
		if( progress != null ) {
			stopNative();
			dismissProgress();
		}
		String launchIfDifferent = null;
		// Don't regenerate on resurrecting a URL-bound activity from the recent list
		if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
			String s = intent.getStringExtra("game");
			Uri u = intent.getData();
			if (s != null && s.length() > 0) {
				Log.d(TAG, "starting game from Intent, " + s.length() + " bytes");
				startGame(GameLaunch.ofSavedGame(s, false));
				return;
			} else if (u != null) {
				Log.d(TAG, "URI is: \"" + u + "\"");
				String g = u.getSchemeSpecificPart();
				if (games.length < 2) games = getResources().getStringArray(R.array.games);
				for (String game : games) {
					if (game.equals(g)) {
						if (game.equals(currentBackend) && !everCompleted) {
							// already alive & playing incomplete game of that kind; keep it.
							return;
						}
						launchIfDifferent = game;
						break;
					}
				}
				if (launchIfDifferent == null) {
					Log.e(TAG, "Unhandled URL! \"" + u + "\" -> g = \"" + g + "\", games = " + Arrays.toString(games));
					// TODO! Other URLs, including game states...
				}
			}
		}
		if( state.contains(SAVED_GAME) && state.getString(SAVED_GAME, "").length() > 0 ) {
			String savedGame = state.getString(SAVED_GAME, "");
			final boolean wasCompleted = state.getBoolean(SAVED_COMPLETED, false);
			if (launchIfDifferent != null) {
				if (wasCompleted || !launchIfDifferent.equals(games[identifyBackend(savedGame)])) {
					Log.d(TAG, "generating as requested");
					startGame(GameLaunch.toGenerateFromChooser(launchIfDifferent));
					return;
				} else {
					Log.d(TAG, "state matches Intent, will keep it");
				}
			}
			Log.d(TAG, "restoring last state");
			startGame(GameLaunch.ofSavedGame(savedGame, wasCompleted));
		} else if (launchIfDifferent != null) {
			// first ever click from chooser
			startGame(GameLaunch.toGenerateFromChooser(launchIfDifferent));
		} else {
			Log.d(TAG, "no state, starting chooser");
			startChooserAndFinish();
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		super.onCreateOptionsMenu(menu);
		this.menu = menu;
		getMenuInflater().inflate(R.menu.main, menu);
		applyUndoRedoKbd();
		return true;
	}

	private Menu hackForSubmenus;
	@Override
	public boolean onPrepareOptionsMenu(Menu menu)
	{
		super.onPrepareOptionsMenu(menu);
		hackForSubmenus = menu;
		final MenuItem solveItem = menu.findItem(R.id.solve);
		solveItem.setEnabled(solveEnabled);
		solveItem.setVisible(solveEnabled);
		updateUndoRedoEnabled();
		final MenuItem typeItem = menu.findItem(R.id.type);
		typeItem.setEnabled(! gameTypes.isEmpty() || customVisible);
		typeItem.setVisible(typeItem.isEnabled());
		typeMenu = typeItem.getSubMenu();
		int i = 0;
		for(Entry<String, String> entry : gameTypes.entrySet()) {
			if( menu.findItem(i) == null ) {
				typeMenu.add(R.id.typeGroup, i, Menu.NONE, entry.getValue());
			}
			i++;
		}
		final MenuItem customItem = menu.findItem(R.id.custom);
		customItem.setVisible(customVisible);
		typeMenu.setGroupCheckable(R.id.typeGroup, true, true);
		if( currentType < 0 ) customItem.setChecked(true);
		else if( currentType < gameTypes.size() ) menu.findItem(currentType).setChecked(true);
		menu.findItem(R.id.this_game).setTitle(MessageFormat.format(
					getString(R.string.how_to_play_game),new Object[]{this.getTitle()}));
		return true;
	}

	private void updateUndoRedoEnabled() {
		final MenuItem undoItem = menu.findItem(R.id.undo);
		final MenuItem redoItem = menu.findItem(R.id.redo);
		undoItem.setEnabled(undoEnabled);
		redoItem.setEnabled(redoEnabled);
		undoItem.setIcon(undoEnabled ? R.drawable.ic_action_undo : R.drawable.ic_action_undo_disabled);
		redoItem.setIcon(redoEnabled ? R.drawable.ic_action_redo : R.drawable.ic_action_redo_disabled);
	}

	static void showHelp(Context context, String topic)
	{
		final Dialog d = new Dialog(context,android.R.style.Theme);
		final WebView wv = new WebView(context);
		d.setOnKeyListener(new DialogInterface.OnKeyListener(){ public boolean onKey(DialogInterface di, int key, KeyEvent evt) {
			if (evt.getAction() != KeyEvent.ACTION_DOWN || key != KeyEvent.KEYCODE_BACK) return false;
			if (wv.canGoBack()) wv.goBack(); else d.cancel();
			return true;
		}});
		d.setContentView(wv);
		wv.setWebChromeClient(new WebChromeClient(){
			public void onReceivedTitle(WebView w, String title) { d.setTitle(title); }
			// onReceivedTitle doesn't happen on back button :-(
			public void onProgressChanged(WebView w, int progress) { if (progress == 100) d.setTitle(w.getTitle()); }
		});
		wv.getSettings().setBuiltInZoomControls(true);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			wv.getSettings().setDisplayZoomControls(false);
		}
		final Resources resources = context.getResources();
		String lang = resources.getConfiguration().locale.getLanguage();
		String assetPath = helpPath(lang, topic);
		try {
			if (resources.getAssets().list(assetPath).length == 0) {
				assetPath = helpPath("en", topic);
			}
		} catch (IOException e) {
			assetPath = helpPath("en", topic);
		}
		wv.loadUrl("file:///android_asset/" + assetPath);
		d.show();
	}

	private static String helpPath(String lang, String topic) {
		return MessageFormat.format("{0}/{1}.html", lang, topic);
	}

	private void startChooserAndFinish()
	{
		NavUtils.navigateUpFromSameTask(this);
		overridePendingTransition(0, 0);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		int itemId = item.getItemId();
		boolean ret = true;
		switch(itemId) {
		case android.R.id.home:
			startChooserAndFinish();
			break;
		case R.id.settings:
			startActivity(new Intent(this, PrefsActivity.class));
			break;
		case R.id.newgame:
			startNewGame();
			break;
		case R.id.restart:  restartEvent(); break;
		case R.id.undo:     sendKey(0, 0, 'u'); break;
		case R.id.redo:     sendKey(0, 0, 'r'); break;
		case R.id.solve:
			try {
				solveEvent();
			} catch (IllegalArgumentException e) {
				messageBox(this, getString(R.string.Error), e.getMessage());
			}
			break;
		case R.id.custom:
			configEvent(CFG_SETTINGS);
			break;
		case R.id.this_game: showHelp(this, htmlHelpTopic()); break;
		case R.id.email:
			startActivity(new Intent(this, SendFeedbackActivity.class));
			break;
		case R.id.save:
			new FilePicker(this, storageDir, true).show();
			break;
		default:
			final int pos = item.getItemId();
			if (pos < gameTypes.size()) {
				String presetParams = (String)gameTypes.keySet().toArray()[pos];
				Log.d(TAG, "preset: " + pos + ": " + presetParams);
				startGame(GameLaunch.toGenerate(currentBackend, presetParams));
			} else {
				ret = super.onOptionsItemSelected(item);
			}
			break;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			// https://code.google.com/p/android/issues/detail?id=69205
			supportInvalidateOptionsMenu();
			rethinkActionBarCapacity();
		}
		return ret;
	}

	static String getEmailSubject(Context c)
	{
		String modVer = "";
		try {
			Process p = Runtime.getRuntime().exec(new String[]{"getprop","ro.modversion"});
			modVer = readAllOf(p.getInputStream()).trim();
		} catch (Exception ignored) {}
		if (modVer.length() == 0) modVer = "original";
		return MessageFormat.format(c.getString(R.string.email_subject),
				getVersion(c), Build.MODEL, modVer, Build.FINGERPRINT);
	}

	private void abort(String why)
	{
		workerRunning = false;
		handler.obtainMessage(MsgType.ABORT.ordinal(), 0, 0, why).sendToTarget();
	}

	static String getVersion(Context c)
	{
		try {
			return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
		} catch(Exception e) { return c.getString(R.string.unknown_version); }
	}

	private String generateGame(final String whichBackend, String params) throws IllegalArgumentException, IOException {
		String game;
		startGameGenProcess(whichBackend, params);
		OutputStream stdin = gameGenProcess.getOutputStream();
		stdin.close();
		game = readAllOf(gameGenProcess.getInputStream());
		if (game.length() == 0) game = null;
		int exitStatus = waitForProcess(gameGenProcess);
		if (exitStatus != 0) {
			String error = game;
			if (error != null && error.length() > 0) {  // probably bogus params
				throw new IllegalArgumentException(error);
			} else if (workerRunning) {
				error = "Game generation exited with status "+exitStatus;
				Log.e(TAG, error);
				throw new IOException(error);
			}
			// else cancelled
		}
		if( !workerRunning) return null;  // cancelled
		return game;
	}

	private void startGameGenProcess(final String whichBackend, String params) throws IOException {
		final File dataDir = new File(getApplicationInfo().dataDir);
		final File libDir = new File(dataDir, "lib");
		final boolean canRunPIE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
		final String suffix = canRunPIE ? "-with-pie" : "-no-pie";
		File installablePath = new File(libDir, "libpuzzlesgen" + suffix + ".so");
		File executablePath = new File(dataDir, "puzzlesgen" + suffix);
		if (!executablePath.exists() ||
				executablePath.lastModified() < installablePath.lastModified()) {
			copyFile(installablePath, executablePath);
			int chmodExit = waitForProcess(Runtime.getRuntime().exec(new String[] {
					"/system/bin/chmod", "755", executablePath.getAbsolutePath()}));
			if (chmodExit > 0) {
				throw new IOException("Can't make game binary executable.");
			}
		}
		gameGenProcess = Runtime.getRuntime().exec(new String[]{
				executablePath.getAbsolutePath(), whichBackend, stringOrEmpty(params)},
				new String[]{"LD_LIBRARY_PATH="+libDir}, libDir);
	}

	private void copyFile(File src, File dst) throws IOException {
		InputStream in = new FileInputStream(src);
		OutputStream out = new FileOutputStream(dst);
		byte[] buf = new byte[8192];
		int len;
		while ((len = in.read(buf)) > 0) {
			out.write(buf, 0, len);
		}
		in.close();
		out.close();
	}

	private int waitForProcess(Process process) {
		if (process == null) return -1;
		try {
			while (true) {
				try {
					return process.waitFor();
				} catch (InterruptedException ignored) {}
			}
		} finally {
			process.destroy();
		}
	}

	private String stringOrEmpty(String s) {
		return (s == null) ? "" : s;
	}

	private void startNewGame()
	{
		startGame(GameLaunch.toGenerate(currentBackend, getCurrentParams()));
	}

	private void startGame(final GameLaunch launch)
	{
		Log.d(TAG, "startGame: " + launch);
		if (progress != null) {
			Log.e(TAG, "startGame while already starting!");
			return;
		}
		showProgress(launch.needsGenerating() ? R.string.starting : R.string.resuming, launch.isFromChooser());
		stopNative();
		startGameThread(launch);
	}

	@UsedByJNI
	private void clearForNewGame(final String keys, final SmallKeyboard.ArrowMode arrowMode, final float[] colours) {
		runOnUiThread(new Runnable(){public void run() {
			gameView.colours = new int[colours.length/3];
			for (int i=0; i<colours.length/3; i++) {
				final int colour = Color.rgb(
						(int) (colours[i * 3    ] * 255),
						(int) (colours[i * 3 + 1] * 255),
						(int) (colours[i * 3 + 2] * 255));
				gameView.colours[i] = colour;
			}
			if (gameView.colours.length > 0) {
				gameView.setBackgroundColor(gameView.colours[0]);
			} else {
				gameView.setBackgroundColor(gameView.getDefaultBackgroundColour());
			}
			gameView.clear();
			solveEnabled = false;
			changedState(false, false);
			customVisible = false;
			setStatusBarVisibility(false);
			applyUndoRedoKbd();
			setKeys(keys, arrowMode);
			if (typeMenu != null) {
				while (typeMenu.size() > 1) typeMenu.removeItem(typeMenu.getItem(0).getItemId());
			}
			gameTypes.clear();
			gameView.keysHandled = 0;
			everCompleted = false;
		}});
	}

	private void startGameThread(final GameLaunch launch) {
		workerRunning = true;
		(worker = new Thread(launch.needsGenerating() ? "generateAndLoadGame" : "loadGame") { public void run() {
			try {
				if (launch.needsGenerating()) {
					String whichBackend = launch.getWhichBackend();
					String params = launch.getParams();
					if (params == null) {
						params = getLastParams(whichBackend);
						Log.d(TAG, "Using last params: "+params);
					} else {
						Log.d(TAG, "Using specified params: "+params);
					}
					String generated = generateGame(whichBackend, params);
					if (generated != null) {
						launch.finishedGenerating(generated);
					} else if (workerRunning) {
						throw new IOException("Internal error generating game: result is blank");
					}
				}
				String toPlay = launch.getSaved();
				if (toPlay == null) {
					Log.d(TAG, "startGameThread: null game, presumably cancelled");
				} else {
					startPlaying(gameView, toPlay);
					if (launch.isKnownCompleted()) {
						runOnUiThread(new Runnable() {
							@Override
							public void run() {
								completed();
							}
						});
					}
				}
			} catch (IllegalArgumentException e) {
				abort(e.getMessage());  // probably bogus params
			} catch (IOException e) {
				abort(e.getMessage());  // internal error :-(
			}
			if (! workerRunning) return;  // stopNative or abort was called
			handler.sendEmptyMessage(MsgType.DONE.ordinal());
		}}).start();
	}

	private String getLastParams(final String whichBackend) {
		return state.getString(LAST_PARAMS_PREFIX + whichBackend, null);
	}

	private void stopNative()
	{
		workerRunning = false;
		if (gameGenProcess != null) {
			gameGenProcess.destroy();
			gameGenProcess = null;
		}
		if (worker != null) {
			while(true) { try {
				worker.join();  // we may ANR if native code is spinning - safer than leaving a runaway native thread
				break;
			} catch (InterruptedException ignored) {} }
		}
	}

	@Override
	protected void onPause()
	{
		handler.removeMessages(MsgType.TIMER.ordinal());
		save();
		super.onPause();
	}

	private boolean restartOnResume = false;

	@Override
	protected void onResume()
	{
		super.onResume();
		if (restartOnResume) {
			startActivity(new Intent(this, RestartActivity.class));
			finish();
		}
	}

	@Override
	protected void onDestroy()
	{
		isAlive = false;
		stopNative();
		super.onDestroy();
	}

	@Override
	public void onWindowFocusChanged( boolean f )
	{
		if( f && gameWantsTimer && currentBackend != null
				&& ! handler.hasMessages(MsgType.TIMER.ordinal()) )
			handler.sendMessageDelayed(handler.obtainMessage(MsgType.TIMER.ordinal()),
					TIMER_INTERVAL);
	}

	void sendKey(int x, int y, int k)
	{
		if (progress != null || currentBackend == null) return;
		if (k == '\f') {
			// menu button hack
			openOptionsMenu();
			return;
		}
		keyEvent(x, y, k);
		gameView.requestFocus();
		if (startedFullscreen) {
			lightsOut(true);
		}
	}

	private boolean prevLandscape = false;
	private void setKeyboardVisibility(Configuration c)
	{
		boolean landscape = (c.orientation == Configuration.ORIENTATION_LANDSCAPE);
		if (landscape != prevLandscape || keyboard == null) {
			// Must recreate KeyboardView on orientation change because it
			// caches the x,y for its preview popups
			// http://code.google.com/p/android/issues/detail?id=4559
			if (keyboard != null) mainLayout.removeView(keyboard);
			keyboard = new SmallKeyboard(this, undoEnabled, redoEnabled);
			keyboard.setId(R.id.keyboard);
			RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
					RelativeLayout.LayoutParams.WRAP_CONTENT,
					RelativeLayout.LayoutParams.WRAP_CONTENT);
			RelativeLayout.LayoutParams glp = new RelativeLayout.LayoutParams(
					RelativeLayout.LayoutParams.WRAP_CONTENT,
					RelativeLayout.LayoutParams.WRAP_CONTENT);
			if (landscape) {
				lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
				lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
				lp.addRule(RelativeLayout.ABOVE, R.id.statusBar);
				glp.addRule(RelativeLayout.ABOVE, R.id.statusBar);
				glp.addRule(RelativeLayout.LEFT_OF, R.id.keyboard);
			} else {
				lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
				lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
				lp.addRule(RelativeLayout.ABOVE, R.id.statusBar);
				glp.addRule(RelativeLayout.ABOVE, R.id.keyboard);
			}
			mainLayout.updateViewLayout(gameView, glp);
			mainLayout.addView(keyboard, lp);
		}
		keyboard.setKeys( (c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO)
				? maybeUndoRedo : filterKeys() + maybeUndoRedo, lastArrowMode);
		prevLandscape = landscape;
		mainLayout.requestLayout();
	}

	private String filterKeys() {
		return lastKeys.replace("h", prefs.getBoolean(BRIDGES_SHOW_H_KEY, false) ? "H" : "");
	}

	@SuppressLint("InlinedApi")
	private void setStatusBarVisibility(boolean visible)
	{
		if (!visible) statusBar.setText("");
		RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
				RelativeLayout.LayoutParams.MATCH_PARENT,
				visible ? RelativeLayout.LayoutParams.WRAP_CONTENT : 0);
		lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
		lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
		mainLayout.updateViewLayout(statusBar, lp);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig)
	{
		if (keysAlreadySet) setKeyboardVisibility(newConfig);
		super.onConfigurationChanged(newConfig);
		rethinkActionBarCapacity();
	}

	/** ActionBar's capacity (width) has probably changed, so work around
	 *  http://code.google.com/p/android/issues/detail?id=20493
	 * (invalidateOptionsMenu() does not help here) */
	private void rethinkActionBarCapacity() {
		if (menu == null) return;
		DisplayMetrics dm = getResources().getDisplayMetrics();
		final int screenWidthDIP = (int) Math.round(((double) dm.widthPixels) / dm.density);
		int state = MenuItemCompat.SHOW_AS_ACTION_ALWAYS;
		if (screenWidthDIP >= 480) {
			state |= MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT;
		}
		MenuItemCompat.setShowAsAction(menu.findItem(R.id.game), state);
		MenuItemCompat.setShowAsAction(menu.findItem(R.id.type), state);
		MenuItemCompat.setShowAsAction(menu.findItem(R.id.help), state);
		final boolean undoRedoKbd = prefs.getBoolean(UNDO_REDO_KBD_KEY, UNDO_REDO_KBD_DEFAULT);
		final MenuItem undoItem = menu.findItem(R.id.undo);
		undoItem.setVisible(!undoRedoKbd);
		final MenuItem redoItem = menu.findItem(R.id.redo);
		redoItem.setVisible(!undoRedoKbd);
		if (!undoRedoKbd) {
			MenuItemCompat.setShowAsAction(undoItem, state);
			MenuItemCompat.setShowAsAction(redoItem, state);
			updateUndoRedoEnabled();
		}
		// emulator at 598 dip looks bad with title+undo; GT-N7100 at 640dip looks good
		getSupportActionBar().setDisplayShowTitleEnabled(screenWidthDIP > 620 || undoRedoKbd);
	}

	@UsedByJNI
	void gameStarted(final String whichBackend, final String title, final boolean hasCustom, final boolean hasStatus, final boolean canSolve)
	{
		runOnUiThread(new Runnable(){public void run(){
			currentBackend = whichBackend;
			customVisible = hasCustom;
			solveEnabled = canSolve;
			setTitle(title);
			getSupportActionBar().setTitle(title);
			setStatusBarVisibility(hasStatus);
		}});
	}

	@UsedByJNI
	void addTypeItem(final String encoded, final String label)
	{
		runOnUiThread(new Runnable(){public void run(){
//			Log.d(TAG, "addTypeItem(" + encoded + ", " + label + ")");
			gameTypes.put(encoded, label);
		}});
	}

	private static void messageBox(final Context context, final String title, final String msg)
	{
		new AlertDialog.Builder(context)
				.setTitle(title)
				.setMessage(msg)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.show();
	}

	@UsedByJNI
	void showToast(final String msg, final boolean fromPattern) {
		if (fromPattern && ! prefs.getBoolean(PATTERN_SHOW_LENGTHS_KEY, false)) return;
		runOnUiThread(new Runnable() {
			public void run() {
				Toast.makeText(GamePlay.this, msg, Toast.LENGTH_SHORT).show();
			}
		});
	}

	@UsedByJNI
	void completed()
	{
		everCompleted = true;
		if (! prefs.getBoolean(COMPLETED_PROMPT_KEY, true)) {
			Toast.makeText(GamePlay.this, getString(R.string.COMPLETED), Toast.LENGTH_SHORT).show();
			return;
		}
		final Dialog d = new Dialog(this, R.style.Dialog_Completed);
		WindowManager.LayoutParams lp = d.getWindow().getAttributes();
		lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
		d.getWindow().setAttributes(lp);
		d.setContentView(R.layout.completed);
		d.setCanceledOnTouchOutside(true);
		final Button newButton = (Button) d.findViewById(R.id.newgame);
		darkenTopDrawable(newButton);
		newButton.setOnClickListener(new OnClickListener() {
			@Override public void onClick(View v) {
				d.dismiss();
				startNewGame();
			}
		});
		final Button typeButton = (Button) d.findViewById(R.id.type);
		darkenTopDrawable(typeButton);
		typeButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				d.dismiss();
				if (hackForSubmenus == null) openOptionsMenu();
				hackForSubmenus.performIdentifierAction(R.id.type, 0);
			}
		});
		final String style = prefs.getString(GameChooser.CHOOSER_STYLE_KEY, "list");
		final boolean useGrid = (style != null) && style.equals("grid");
		final Button chooserButton = (Button) d.findViewById(R.id.other);
		chooserButton.setCompoundDrawablesWithIntrinsicBounds(0, useGrid
				? R.drawable.ic_action_view_as_grid
				: R.drawable.ic_action_view_as_list, 0, 0);
		darkenTopDrawable(chooserButton);
		chooserButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				d.dismiss();
				startChooserAndFinish();
			}
		});
		d.show();
	}

	private void darkenTopDrawable(Button b) {
		final Drawable drawable = b.getCompoundDrawables()[1].mutate();
		drawable.setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_ATOP);
		b.setCompoundDrawables(null, drawable, null, null);
	}

	@UsedByJNI
	void setStatus(final String status)
	{
		runOnUiThread(new Runnable() {
			public void run() {
				statusBar.setText(status.length() == 0 ? " " : status);
			}
		});
	}

	@UsedByJNI
	void requestTimer(boolean on)
	{
		if( gameWantsTimer && on ) return;
		gameWantsTimer = on;
		if( on ) handler.sendMessageDelayed(handler.obtainMessage(MsgType.TIMER.ordinal()), TIMER_INTERVAL);
		else handler.removeMessages(MsgType.TIMER.ordinal());
	}

	@UsedByJNI
	void dialogInit(int whichEvent, String title)
	{
		ScrollView sv = new ScrollView(GamePlay.this);
		AlertDialog.Builder builder = new AlertDialog.Builder(GamePlay.this)
				.setTitle(title)
				.setView(sv)
				.setOnCancelListener(new OnCancelListener() {
					public void onCancel(DialogInterface dialog) {
						configCancel();
					}
				})
				.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface d, int whichButton) {
						for (String i : dialogIds) {
							View v = dialogLayout.findViewWithTag(i);
							if (v instanceof EditText) {
								configSetString(i, ((EditText) v).getText().toString());
							} else if (v instanceof CheckBox) {
								configSetBool(i, ((CheckBox) v).isChecked() ? 1 : 0);
							} else if (v instanceof Spinner) {
								configSetChoice(i, ((Spinner) v).getSelectedItemPosition());
							}
						}
						dialog.dismiss();
						try {
							startGame(GameLaunch.toGenerate(currentBackend, configOK()));
						} catch (IllegalArgumentException e) {
							dismissProgress();
							messageBox(GamePlay.this, getString(R.string.Error), e.getMessage());
						}
					}
				});
		if (whichEvent == CFG_SETTINGS) {
			builder.setNegativeButton(R.string.Game_ID_, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					configEvent(CFG_DESC);
				}
			})
			.setNeutralButton(R.string.Seed_, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					configEvent(CFG_SEED);
				}
			});
		}
		dialog = builder.create();
		sv.addView(dialogLayout = new TableLayout(GamePlay.this));
		final int xPadding = getResources().getDimensionPixelSize(R.dimen.dialog_padding_horizontal);
		final int yPadding = getResources().getDimensionPixelSize(R.dimen.dialog_padding_vertical);
		dialogLayout.setPadding(xPadding, yPadding, xPadding, yPadding);
		dialogIds.clear();
	}

	@SuppressLint("InlinedApi")
	@UsedByJNI
	void dialogAdd(int whichEvent, int type, String name, String value, int selection)
	{
		switch(type) {
		case C_STRING: {
			dialogIds.add(name);
			EditText et = new EditText(GamePlay.this);
			// TODO: C_INT, C_UINT, C_UDOUBLE, C_DOUBLE
			// Ugly temporary hack: in custom game dialog, all text boxes are numeric, in the other two dialogs they aren't.
			// Uglier temporary-er hack: Black Box must accept a range for ball count.
			if (whichEvent == CFG_SETTINGS && !currentBackend.equals("blackbox")) et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
			et.setTag(name);
			et.setText(value);
			et.setWidth(getResources().getDimensionPixelSize((whichEvent == CFG_SETTINGS)
					? R.dimen.dialog_edit_text_width : R.dimen.dialog_long_edit_text_width));
			et.setSelectAllOnFocus(true);
			TextView tv = new TextView(GamePlay.this);
			tv.setText(name);
			tv.setPadding(0, 0, getResources().getDimensionPixelSize(R.dimen.dialog_padding_horizontal), 0);
			tv.setGravity(Gravity.END);
			TableRow tr = new TableRow(GamePlay.this);
			tr.addView(tv);
			tr.addView(et);
			tr.setGravity(Gravity.CENTER_VERTICAL);
			dialogLayout.addView(tr);
			break; }
		case C_BOOLEAN: {
			dialogIds.add(name);
			CheckBox c = new CheckBox(GamePlay.this);
			c.setTag(name);
			c.setText(name);
			c.setChecked(selection != 0);
			dialogLayout.addView(c);
			break; }
		case C_CHOICES: {
			StringTokenizer st = new StringTokenizer(value.substring(1),value.substring(0,1));
			ArrayList<String> choices = new ArrayList<String>();
			while(st.hasMoreTokens()) choices.add(st.nextToken());
			dialogIds.add(name);
			Spinner s = new Spinner(GamePlay.this);
			s.setTag(name);
			ArrayAdapter<String> a = new ArrayAdapter<String>(GamePlay.this,
					android.R.layout.simple_spinner_item, choices.toArray(new String[choices.size()]));
			a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			s.setAdapter(a);
			s.setSelection(selection);
			s.setLayoutParams(new TableRow.LayoutParams(
					getResources().getDimensionPixelSize(R.dimen.dialog_spinner_width),
					TableRow.LayoutParams.WRAP_CONTENT));
			TextView tv = new TextView(GamePlay.this);
			tv.setText(name);
			tv.setPadding(0, 0, getResources().getDimensionPixelSize(R.dimen.dialog_padding_horizontal), 0);
			tv.setGravity(Gravity.END);
			TableRow tr = new TableRow(GamePlay.this);
			tr.addView(tv);
			tr.addView(s);
			tr.setGravity(Gravity.CENTER_VERTICAL);
			dialogLayout.addView(tr);
			break; }
		}
	}

	@UsedByJNI
	void dialogShow()
	{
		dialogLayout.setColumnShrinkable(0, true);
		dialogLayout.setColumnShrinkable(1, true);
		dialogLayout.setColumnStretchable(0, true);
		dialogLayout.setColumnStretchable(1, true);
		if (getResources().getConfiguration().hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) {
			// Hack to prevent the first EditText from receiving focus when the dialog is shown
			dialogLayout.setFocusableInTouchMode(true);
			dialogLayout.requestFocus();
		}
		dialog.show();
	}

	@UsedByJNI
	void tickTypeItem(int whichType)
	{
		currentType = whichType;
	}

	@UsedByJNI
	void serialiseWrite(byte[] buffer)
	{
		savingState.append(new String(buffer));
	}

	private SmallKeyboard.ArrowMode lastArrowMode = SmallKeyboard.ArrowMode.NO_ARROWS;

	private void setKeys(String keys, SmallKeyboard.ArrowMode arrowMode)
	{
		if (keys == null) keys = "";
		if (arrowMode == null) arrowMode = SmallKeyboard.ArrowMode.ARROWS_LEFT_RIGHT_CLICK;
		lastArrowMode = arrowMode;
		lastKeys = keys;
		setKeyboardVisibility(getResources().getConfiguration());
		keysAlreadySet = true;
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences p, String key)
	{
		if (key.equals(ARROW_KEYS_KEY) || key.equals(INERTIA_FORCE_ARROWS_KEY)) {
			setKeyboardVisibility(getResources().getConfiguration());
		} else if (key.equals(FULLSCREEN_KEY)) {
			applyFullscreen(true);  // = already started
		} else if (key.equals(STAY_AWAKE_KEY)) {
			applyStayAwake();
		} else if (key.equals(ORIENTATION_KEY)) {
			applyOrientation();
		} else if (key.equals(UNDO_REDO_KBD_KEY)) {
			applyUndoRedoKbd();
		} else if (key.equals(BRIDGES_SHOW_H_KEY)) {
			applyBridgesShowH();
		} else if (key.equals(GameChooser.CHOOSER_STYLE_KEY)) {
			applyChooserIcon();
		}
	}

	private void applyChooserIcon() {
		final String style = prefs.getString(GameChooser.CHOOSER_STYLE_KEY, "list");
		final boolean useGrid = (style != null) && style.equals("grid");
		getSupportActionBar().setDisplayUseLogoEnabled(false);
		getSupportActionBar().setIcon(useGrid
				? R.drawable.ic_action_view_as_grid
				: R.drawable.ic_action_view_as_list);
	}

	private void applyFullscreen(boolean alreadyStarted) {
		cachedFullscreen = prefs.getBoolean(FULLSCREEN_KEY, false);
		final boolean hasLightsOut = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB);
		if (cachedFullscreen) {
			if (hasLightsOut) {
				handler.post(new Runnable() {
					public void run() {
						lightsOut(true);
					}
				});
			} else if (alreadyStarted) {
				// This is the only way to change the theme
				if (! startedFullscreen) restartOnResume = true;
			} else {
				setTheme(R.style.SolidActionBar_Gameplay_FullScreen);
			}
		} else {
			if (hasLightsOut) {
				final boolean fAlreadyStarted = alreadyStarted;
				handler.post(new Runnable(){ public void run() {
					lightsOut(false);
					// This shouldn't be necessary but is on Galaxy Tab 10.1
					if (fAlreadyStarted && startedFullscreen) restartOnResume = true;
				}});
			} else if (alreadyStarted && startedFullscreen) {
				// This is the only way to change the theme
				restartOnResume = true;
			}  // else leave it as default non-fullscreen
		}
	}

	private void lightsOut(final boolean fullScreen) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
			if (fullScreen) {
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
			} else {
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
			}
		} else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			SysUIVisSetter.set(gameView, fullScreen);
		}
	}

	private void applyStayAwake()
	{
		if (prefs.getBoolean(STAY_AWAKE_KEY, false)) {
			getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
	}

	@SuppressLint("InlinedApi")
	private void applyOrientation() {
		final String orientationPref = prefs.getString(ORIENTATION_KEY, "sensor");
		if (orientationPref.equals("landscape")) {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		} else if (orientationPref.equals("portrait")) {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
		} else {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
		}
	}

	private void applyUndoRedoKbd() {
		boolean undoRedoKbd = prefs.getBoolean(UNDO_REDO_KBD_KEY, UNDO_REDO_KBD_DEFAULT);
		final String wantKbd = undoRedoKbd ? "ur" : "";
		if (!wantKbd.equals(maybeUndoRedo)) {
			maybeUndoRedo = wantKbd;
			setKeyboardVisibility(getResources().getConfiguration());
		}
		rethinkActionBarCapacity();
	}

	private void applyBridgesShowH() {
		setKeyboardVisibility(getResources().getConfiguration());
	}

	@UsedByJNI
	String gettext(String s)
	{
		if (s.startsWith(":")) {
			String[] choices = s.substring(1).split(":");
			String ret = "";
			for (String choice : choices) ret += ":"+gettext(choice);
			return ret;
		}
		String id = s
				.replaceAll("^([0-9])","_$1")
				.replaceAll("%age","percentage")
				.replaceAll("','","comma")
				.replaceAll("%[.0-9]*u?[sd]","X")
				.replaceAll("[^A-Za-z0-9_]+", "_");
		if( id.endsWith("_") ) id = id.substring(0,id.length()-1);
		int resId = getResources().getIdentifier(id, "string", getPackageName());
		if( resId > 0 ) {
			return getString(resId);
		}
		Log.i(TAG, "gettext: NO TRANSLATION: " + s + " -> " + id + " -> ???");
		return s;
	}

	@UsedByJNI
	void changedState(final boolean canUndo, final boolean canRedo)
	{
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				undoEnabled = canUndo;
				redoEnabled = canRedo;
				if (keyboard != null) {
					keyboard.setUndoRedoEnabled(canUndo, canRedo);
				}
				if (menu != null) {
					MenuItem mi;
					mi = menu.findItem(R.id.undo);
					if (mi != null) {
						mi.setEnabled(undoEnabled);
						mi.setIcon(undoEnabled ? R.drawable.ic_action_undo : R.drawable.ic_action_undo_disabled);
					}
					mi = menu.findItem(R.id.redo);
					if (mi != null) {
						mi.setEnabled(redoEnabled);
						mi.setIcon(redoEnabled ? R.drawable.ic_action_redo : R.drawable.ic_action_redo_disabled);
					}
				}
			}
		});
	}

	private static String readAllOf(InputStream s) throws IOException
	{
		BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(s),8096);
		String line;
		StringBuilder log = new StringBuilder();
		while ((line = bufferedReader.readLine()) != null) {
			log.append(line);
			log.append("\n");
		}
		return log.toString();
	}

	native void startPlaying(GameView _gameView, String savedGame);
	native void timerTick();
	native String htmlHelpTopic();
	native void keyEvent(int x, int y, int k);
	native void restartEvent();
	native void solveEvent();
	native void resizeEvent(int x, int y);
	native void configEvent(int whichEvent);
	native String configOK();
	native void configCancel();
	native void configSetString(String item_ptr, String s);
	native void configSetBool(String item_ptr, int selected);
	native void configSetChoice(String item_ptr, int selected);
	native void serialise();
	native int identifyBackend(String savedGame);
	native String getCurrentParams();

	static {
		System.loadLibrary("puzzles");
	}
}