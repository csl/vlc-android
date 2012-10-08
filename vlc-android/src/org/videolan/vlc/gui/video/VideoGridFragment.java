/*****************************************************************************
 * VideoListActivity.java
 *****************************************************************************
 * Copyright © 2011-2012 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.gui.video;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

import org.videolan.android.ui.SherlockGridFragment;
import org.videolan.vlc.DatabaseManager;
import org.videolan.vlc.Media;
import org.videolan.vlc.MediaLibrary;
import org.videolan.vlc.R;
import org.videolan.vlc.ThumbnailerManager;
import org.videolan.vlc.Util;
import org.videolan.vlc.VlcRunnable;
import org.videolan.vlc.WeakHandler;
import org.videolan.vlc.gui.CommonDialogs;
import org.videolan.vlc.gui.PreferencesActivity;
import org.videolan.vlc.interfaces.ISortable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class VideoGridFragment extends SherlockGridFragment implements ISortable {

    public final static String TAG = "VLC/VideoListFragment";

    protected static final String ACTION_SCAN_START = "org.videolan.vlc.gui.ScanStart";
    protected static final String ACTION_SCAN_STOP = "org.videolan.vlc.gui.ScanStop";
    protected static final int UPDATE_ITEM = 0;

    /* Constants used to switch from Grid to List and vice versa */
    //FIXME If you know a way to do this in pure XML please do it!
    private static final int GRID_ITEM_WIDTH_DP = 156;
    private static final int GRID_HORIZONTAL_SPACING_DP = 20;
    private static final int GRID_VERTICAL_SPACING_DP = 20;
    private static final int GRID_STRETCH_MODE = GridView.STRETCH_SPACING;
    private static final int LIST_HORIZONTAL_SPACING_DP = 0;
    private static final int LIST_VERTICAL_SPACING_DP = 10;
    private static final int LIST_STRETCH_MODE = GridView.STRETCH_COLUMN_WIDTH;

    protected LinearLayout mLayoutFlipperLoading;
    protected TextView mTextViewNomedia;
    protected Media mItemToUpdate;
    protected final CyclicBarrier mBarrier = new CyclicBarrier(2);

    private VideoListAdapter mVideoAdapter;
    private MediaLibrary mMediaLibrary;
    private ThumbnailerManager mThumbnailerManager;

    /* All subclasses of Fragment must include a public empty constructor. */
    public VideoGridFragment() { }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mVideoAdapter = new VideoListAdapter(getActivity());
        mMediaLibrary = MediaLibrary.getInstance(getActivity());
        setListAdapter(mVideoAdapter);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        View v = inflater.inflate(R.layout.video_grid, container, false);

        // init the information for the scan (1/2)
        mLayoutFlipperLoading = (LinearLayout) v.findViewById(R.id.layout_flipper_loading);
        mTextViewNomedia = (TextView) v.findViewById(R.id.textview_nomedia);

        return v;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        registerForContextMenu(getGridView());

        // init the information for the scan (2/2)
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SCAN_START);
        filter.addAction(ACTION_SCAN_STOP);
        getActivity().registerReceiver(messageReceiverVideoListFragment, filter);
        Log.i(TAG,"mMediaLibrary.isWorking() " + Boolean.toString(mMediaLibrary.isWorking()));
        if (mMediaLibrary.isWorking()) {
            actionScanStart(getActivity().getApplicationContext());
        }

        updateList();
    }

    @Override
    public void onPause() {
        super.onPause();
        mMediaLibrary.removeUpdateHandler(mHandler);
    }

    @Override
    public void onResume() {
        super.onResume();
        //Get & highlight the last media
        SharedPreferences preferences = getActivity().getSharedPreferences(PreferencesActivity.NAME, Context.MODE_PRIVATE);
        String lastPath = preferences.getString(PreferencesActivity.LAST_MEDIA, null);
        HashMap<String, Long> times = DatabaseManager.getInstance(getActivity()).getVideoTimes(getActivity());
        mVideoAdapter.setLastMedia(lastPath, times);
        mVideoAdapter.notifyDataSetChanged();
        mMediaLibrary.addUpdateHandler(mHandler);
        updateViewMode();
    }

    @Override
    public void onDestroyView() {
        getActivity().unregisterReceiver(messageReceiverVideoListFragment);
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBarrier.reset();
        mVideoAdapter.clear();
    }

    private boolean hasSpaceForGrid() {
        final Activity activity = getActivity();
        if (activity == null)
            return true;

        final LayoutInflater inflater = (LayoutInflater)activity.getSystemService
                (Context.LAYOUT_INFLATER_SERVICE);
        final View v = inflater.inflate(R.layout.video_grid, null);
        final GridView grid = (GridView)v.findViewById(android.R.id.list);

        DisplayMetrics outMetrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(outMetrics);

        final int itemWidth = Util.convertDpToPx(GRID_ITEM_WIDTH_DP);
        final int horizontalspacing = Util.convertDpToPx(GRID_HORIZONTAL_SPACING_DP);
        final int width = grid.getPaddingLeft() + grid.getPaddingRight() + horizontalspacing + (itemWidth * 2);
        if (width < outMetrics.widthPixels)
            return true;
        return false;
    }

    private void updateViewMode() {
        if (getView() == null) {
            Log.w(TAG, "Unable to setup the view");
            return;
        }

        final GridView gv = (GridView)getView().findViewById(android.R.id.list);
        if (hasSpaceForGrid()) {
            Log.d(TAG, "Switching to grid mode");
            gv.setNumColumns(GridView.AUTO_FIT);
            gv.setStretchMode(GRID_STRETCH_MODE);
            gv.setHorizontalSpacing(Util.convertDpToPx(GRID_HORIZONTAL_SPACING_DP));
            gv.setVerticalSpacing(Util.convertDpToPx(GRID_VERTICAL_SPACING_DP));
            gv.setColumnWidth(Util.convertDpToPx(GRID_ITEM_WIDTH_DP));
            mVideoAdapter.setListMode(false);
        } else {
            Log.e(TAG, "Switching to list mode");
            gv.setNumColumns(1);
            gv.setStretchMode(LIST_STRETCH_MODE);
            gv.setHorizontalSpacing(LIST_HORIZONTAL_SPACING_DP);
            gv.setVerticalSpacing(Util.convertDpToPx(LIST_VERTICAL_SPACING_DP));
            mVideoAdapter.setListMode(true);
        }
        gv.forceLayout();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            updateViewMode();
        }
    }

    @Override
    public void onGridItemClick(GridView l, View v, int position, long id) {
        playVideo(position);
        super.onGridItemClick(l, v, position, id);
    }

    protected void playVideo(int position) {
        Media item = (Media) getListAdapter().getItem(position);
        VideoPlayerActivity.start(getActivity(), item.getLocation());
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.menu.video_list, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem menu) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menu.getMenuInfo();
        switch (menu.getItemId())
        {
        case R.id.video_list_play:
            playVideo(info.position);
            return true;
        case R.id.video_list_info:
            Intent intent = new Intent(getActivity(), MediaInfoActivity.class);
            intent.putExtra("itemLocation",
                    mVideoAdapter.getItem(info.position).getLocation());
            startActivity(intent);
            return true;
        case R.id.video_list_delete:
            final int positionDelete = info.position;
            AlertDialog alertDialog = CommonDialogs.deleteMedia(
                    getActivity(),
                    mVideoAdapter.getItem(positionDelete).getLocation(),
                    new VlcRunnable() {
                        @Override
                        public void run(Object o) {
                            mVideoAdapter.remove(mVideoAdapter.getItem(positionDelete));
                        }
                    });
            alertDialog.show();
            return true;
        }
        return super.onContextItemSelected(menu);
    }

    /**
     * Handle changes on the list
     */
    private Handler mHandler = new VideoListHandler(this);

    private static class VideoListHandler extends WeakHandler<VideoGridFragment> {
        public VideoListHandler(VideoGridFragment owner) {
            super(owner);
        }

        @Override
        public void handleMessage(Message msg) {
            VideoGridFragment fragment = getOwner();
            if(fragment == null) return;

            switch (msg.what) {
            case UPDATE_ITEM:
                fragment.updateItem();
                break;
            case MediaLibrary.MEDIA_ITEMS_UPDATED:
                fragment.updateList();
                break;
            }
        }
    };

    private void updateItem() {
        mVideoAdapter.update(mItemToUpdate);
        try {
            mBarrier.await();
        } catch (InterruptedException e) {
        } catch (BrokenBarrierException e) {
        }
    }

    private void updateList() {
        List<Media> itemList = mMediaLibrary.getVideoItems();

        if (mThumbnailerManager != null)
            mThumbnailerManager.clearJobs();
        else
            Log.w(TAG, "Can't generate thumbnails, the thumbnailer is missing");

        mVideoAdapter.clear();

        if (itemList.size() > 0) {
            for (Media item : itemList) {
                if (item.getType() == Media.TYPE_VIDEO) {
                    mVideoAdapter.add(item);
                    if (mThumbnailerManager != null && item.getPicture() == null && !item.isPictureParsed())
                        mThumbnailerManager.addJob(item);
                }
            }
            mVideoAdapter.sort();
        }
    }

    @Override
    public void sortBy(int sortby) {
        mVideoAdapter.sortBy(sortby);
    }

    public void setItemToUpdate(Media item) {
        mItemToUpdate = item;
        mHandler.sendEmptyMessage(UPDATE_ITEM);
    }

    public void await() throws InterruptedException, BrokenBarrierException {
        mBarrier.await();
    }

    public void resetBarrier() {
        mBarrier.reset();
    }

    private final BroadcastReceiver messageReceiverVideoListFragment = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equalsIgnoreCase(ACTION_SCAN_START)) {
                mLayoutFlipperLoading.setVisibility(View.VISIBLE);
                mTextViewNomedia.setVisibility(View.INVISIBLE);
            } else if (action.equalsIgnoreCase(ACTION_SCAN_STOP)) {
                mLayoutFlipperLoading.setVisibility(View.INVISIBLE);
                mTextViewNomedia.setVisibility(View.VISIBLE);
            }
        }
    };

    public static void actionScanStart(Context context) {
        if (context == null)
            return;
        Intent intent = new Intent();
        intent.setAction(ACTION_SCAN_START);
        context.getApplicationContext().sendBroadcast(intent);
    }

    public static void actionScanStop(Context context) {
        if (context == null)
            return;
        Intent intent = new Intent();
        intent.setAction(ACTION_SCAN_STOP);
        context.getApplicationContext().sendBroadcast(intent);
    }

    public void setThumbnailerManager(ThumbnailerManager thumbnailerManager) {
        mThumbnailerManager = thumbnailerManager;
    }
}