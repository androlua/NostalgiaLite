package nostalgia.framework.ui.gamegallery;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TabLayout;
import android.support.v4.view.ViewPager;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;

import nostalgia.framework.R;
import nostalgia.framework.base.EmulatorActivity;
import nostalgia.framework.ui.gamegallery.GalleryPagerAdapter.OnItemClickListener;
import nostalgia.framework.ui.preferences.GeneralPreferenceActivity;
import nostalgia.framework.ui.preferences.GeneralPreferenceFragment;
import nostalgia.framework.ui.preferences.PreferenceUtil;
import nostalgia.framework.ui.remotecontroller.RemoteControllerActivity;
import nostalgia.framework.utils.DatabaseHelper;
import nostalgia.framework.utils.DialogUtils;
import nostalgia.framework.utils.NLog;
import nostalgia.framework.utils.Utils;
import nostalgia.framework.utils.Utils.ServerType;

public abstract class GalleryActivity extends BaseGameGalleryActivity
        implements OnItemClickListener {

    public static final int COMMAND_SEARCHMODE = 1;
    public static final String EXTRA_TABS_IDX = "EXTRA_TABS_IDX";
    private static final String TAG = "GalleryActivity";
    private static final int REQUEST_IMPORT = 2;
    private static final String importPref = "import_pref";

    ProgressDialog searchDialog = null;
    private ViewPager pager = null;
    private DatabaseHelper dbHelper;
    private GalleryPagerAdapter adapter;
    private boolean importing = false;
    private boolean rotateAnim = false;
    private TabLayout mTabLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dbHelper = new DatabaseHelper(this);
        setContentView(R.layout.activity_gallery);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        adapter = new GalleryPagerAdapter(this, this);
        adapter.onRestoreInstanceState(savedInstanceState);
        pager = (ViewPager) findViewById(R.id.game_gallery_pager);
        pager.setAdapter(adapter);

        mTabLayout = (TabLayout) findViewById(R.id.game_gallery_tab);
        mTabLayout.setupWithViewPager(pager);
        mTabLayout.setTabMode(TabLayout.MODE_FIXED);

        if (savedInstanceState != null) {
            pager.setCurrentItem(savedInstanceState.getInt(EXTRA_TABS_IDX, 0));
        } else {
            pager.setCurrentItem(PreferenceUtil.getLastGalleryTab(this));
        }

        exts = getRomExtensions();
        exts.addAll(getArchiveExtensions());
        inZipExts = getRomExtensions();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gallery_main_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (Utils.getDeviceType(this) == ServerType.mobile) {
            MenuItem item = menu.findItem(R.id.gallery_menu_remote_control);
            item.setEnabled(Utils.isWifiAvailable(this));
        } else {
            menu.removeItem(R.id.gallery_menu_remote_control);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.gallery_menu_pref) {
            Intent i = new Intent(this, GeneralPreferenceActivity.class);
            i.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT, GeneralPreferenceFragment.class.getName());
            i.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true);
            startActivity(i);
            return true;
        } else if (itemId == R.id.gallery_menu_remote_control) {
            final Intent intent = new Intent(GalleryActivity.this, RemoteControllerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            rotateAnim = true;
            return true;
        } else if (itemId == R.id.gallery_menu_reload) {
            reloadGames(true, null);
            return true;
        } else if (itemId == R.id.gallery_menu_exit) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rotateAnim) {
            rotateAnim = false;
        }
        adapter.notifyDataSetChanged();
        if (reloadGames && !importing) {
            boolean isDBEmpty = dbHelper.countObjsInDb(GameDescription.class, null) == 0;
            reloadGames(isDBEmpty, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        PreferenceUtil.saveLastGalleryTab(this, pager.getCurrentItem());
    }

    @Override
    public void onItemClick(GameDescription game) {
        File gameFile = new File(game.path);
        NLog.i(TAG, "select " + game);

        if (game.isInArchive()) {
            gameFile = new File(getExternalCacheDir(), game.checksum);
            game.path = gameFile.getAbsolutePath();
            ZipRomFile zipRomFile = dbHelper.selectObjFromDb(ZipRomFile.class,
                    "WHERE _id=" + game.zipfile_id, false);
            File zipFile = new File(zipRomFile.path);
            if (!gameFile.exists()) {
                try {
                    Utils.extractFile(zipFile, game.name, gameFile);
                } catch (IOException e) {
                    NLog.e(TAG, "", e);
                }
            }
        }

        if (gameFile.exists()) {
            game.lastGameTime = System.currentTimeMillis();
            game.runCount++;
            dbHelper.updateObjToDb(game, new String[]{"lastGameTime", "runCount"});
            onGameSelected(game, 0);
        } else {
            NLog.w(TAG, "rom file:" + gameFile.getAbsolutePath() + " does not exist");
            AlertDialog.Builder builder = new AlertDialog.Builder(GalleryActivity.this);
            AlertDialog dialog = builder.setMessage(getString(R.string.gallery_rom_not_found))
                    .setTitle(R.string.error)
                    .setPositiveButton(R.string.gallery_rom_not_found_reload,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                                    int which) {
                                    reloadGames(true, null);
                                }
                            }).create();
            dialog.setCancelable(false);
            dialog.setOnDismissListener(new OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialog) {
                    reloadGames(true, null);
                }
            });
            dialog.show();
        }
    }

    public boolean onGameSelected(GameDescription game, int slot) {
        Intent intent = new Intent(this, getEmulatorActivityClass());
        intent.putExtra(EmulatorActivity.EXTRA_GAME, game);
        intent.putExtra(EmulatorActivity.EXTRA_SLOT, slot);
        intent.putExtra(EmulatorActivity.EXTRA_FROM_GALLERY, true);
        startActivity(intent);
        return true;
    }

    @Override
    public void setLastGames(ArrayList<GameDescription> games) {
        adapter.setGames(games);
        pager.setVisibility(games.isEmpty() ? View.INVISIBLE : View.VISIBLE);

    }

    @Override
    public void setNewGames(ArrayList<GameDescription> games) {
        boolean isListEmpty = adapter.addGames(games) == 0;
        pager.setVisibility(isListEmpty ? View.INVISIBLE : View.VISIBLE);
    }

    private void showSearchProgressDialog(boolean zipMode) {
        if (searchDialog == null) {
            searchDialog = new ProgressDialog(this);
            searchDialog.setMax(100);
            searchDialog.setCancelable(false);
            searchDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            searchDialog.setIndeterminate(true);
            searchDialog.setProgressNumberFormat("");
            searchDialog.setProgressPercentFormat(null);
            searchDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                    getString(R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            stopRomsFinding();
                        }
                    });
        }
        searchDialog.setMessage(getString(zipMode ?
                R.string.gallery_zip_search_label : R.string.gallery_sdcard_search_label));
        DialogUtils.show(searchDialog, false);

    }

    public void onSearchingEnd(final int count, final boolean showToast) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (searchDialog != null) {
                    searchDialog.dismiss();
                    searchDialog = null;
                }
                if (showToast) {
                    if (count > 0) {
                        Snackbar.make(pager,
                                getString(R.string.gallery_count_of_found_games, count),
                                Snackbar.LENGTH_LONG).setAction("Action", null).show();
                    }
                }
            }
        });
    }

    @Override
    public void onRomsFinderStart(boolean searchNew) {
        if (searchNew) {
            showSearchProgressDialog(false);
        }
    }

    @Override
    public void onRomsFinderZipPartStart(final int countEntries) {
        if (searchDialog != null) {
            runOnUiThread(new Runnable() {
                @SuppressLint("NewApi")
                @Override
                public void run() {
                    if (searchDialog != null) {
                        searchDialog.setProgressNumberFormat("%1d/%2d");
                        searchDialog.setProgressPercentFormat(NumberFormat.getPercentInstance());
                        searchDialog.setMessage(getString(R.string.gallery_start_sip_search_label));
                        searchDialog.setIndeterminate(false);
                        searchDialog.setMax(countEntries);
                    }
                }
            });
        }
    }

    @Override
    public void onRomsFinderCancel(boolean searchNew) {
        super.onRomsFinderCancel(searchNew);
        onSearchingEnd(0, searchNew);
    }

    @Override
    public void onRomsFinderEnd(boolean searchNew) {
        super.onRomsFinderEnd(searchNew);
        onSearchingEnd(0, searchNew);
    }

    @Override
    public void onRomsFinderNewGames(ArrayList<GameDescription> roms) {
        super.onRomsFinderNewGames(roms);
        onSearchingEnd(roms.size(), true);
    }

    @Override
    public void onRomsFinderFoundZipEntry(final String message, final int skipEntries) {
        if (searchDialog != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (searchDialog != null) {
                        searchDialog.setMessage(message);
                        searchDialog.setProgress(searchDialog.getProgress() + 1 + skipEntries);
                    }
                }
            });
        }
    }

    @Override
    public void onRomsFinderFoundFile(final String name) {
        if (searchDialog != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (searchDialog != null) {
                        searchDialog.setMessage(name);
                    }
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_TABS_IDX, pager.getCurrentItem());
        adapter.onSaveInstanceState(outState);
    }

}
