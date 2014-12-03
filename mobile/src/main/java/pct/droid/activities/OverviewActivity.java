package pct.droid.activities;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.FragmentManager;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;

import butterknife.InjectView;
import pct.droid.base.Constants;
import pct.droid.R;
import pct.droid.adapters.OverviewGridAdapter;
import pct.droid.base.providers.media.EZTVProvider;
import pct.droid.base.providers.media.types.Movie;
import pct.droid.base.providers.media.types.Show;
import pct.droid.base.utils.LogUtils;
import pct.droid.fragments.OverviewActivityTaskFragment;
import pct.droid.base.providers.media.MediaProvider;
import pct.droid.base.providers.media.YTSProvider;
import pct.droid.base.providers.media.types.Media;
import pct.droid.base.providers.subs.SubsProvider;
import pct.droid.base.utils.PixelUtils;
import pct.droid.base.youtube.YouTubeData;

public class OverviewActivity extends BaseActivity implements MediaProvider.Callback {

    private OverviewActivityTaskFragment mTaskFragment;
    private OverviewGridAdapter mAdapter;
    private GridLayoutManager mLayoutManager;
    private MediaProvider mProvider = new YTSProvider();
    private Integer mProviderId = 0;
    private Integer mColumns = 2, mRetries = 0;
    private boolean mLoading = true, mEndOfListReached = false, mLoadingDetails = false;
    private int mFirstVisibleItem, mVisibleItemCount, mTotalItemCount = 0, mLoadingTreshold = mColumns * 3, mPreviousTotal = 0;

    @InjectView(R.id.toolbar)
    Toolbar toolbar;
    @InjectView(R.id.progressOverlay)
    LinearLayout progressOverlay;
    @InjectView(R.id.recyclerView)
    RecyclerView recyclerView;
    @InjectView(R.id.emptyView)
    TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState, R.layout.activity_overview);
        setSupportActionBar(toolbar);

        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.JELLY_BEAN_MR2) {
            toolbar.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, getResources().getDimensionPixelSize(R.dimen.abc_action_bar_default_height_material) + PixelUtils.getStatusBarHeight(this)));
        } else {
            toolbar.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, getResources().getDimensionPixelSize(R.dimen.abc_action_bar_default_height_material)));
        }

        /* Temporary */
        final GestureDetectorCompat detector = new GestureDetectorCompat(this, new GestureDetector.SimpleOnGestureListener());

        detector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent motionEvent) {
                mProvider.cancel();
                if(mProviderId == 0) {
                    mProvider = new EZTVProvider();
                    mProviderId = 1;
                } else {
                    mProvider = new YTSProvider();
                    mProviderId = 0;
                }

                mTaskFragment.setFilters(new HashMap<String, String>());
                mTaskFragment.setCurrentPage(0);
                mAdapter = new OverviewGridAdapter(OverviewActivity.this, new ArrayList<Media>(), mColumns);
                mAdapter.setOnItemClickListener(mOnItemClickListener);
                progressOverlay.setVisibility(View.VISIBLE);
                recyclerView.setAdapter(mAdapter);
                mPreviousTotal = mTotalItemCount = mAdapter.getItemCount();

                mProvider.getList(mTaskFragment.getFilters(), mTaskFragment);
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent motionEvent) {
                return false;
            }
        });

        toolbar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                return detector.onTouchEvent(motionEvent);
            }
        });
        /* End temporary */

        recyclerView.setHasFixedSize(true);
        mColumns = getResources().getInteger(R.integer.overview_cols);
        mLoadingTreshold = mColumns * 3;
        mLayoutManager = new GridLayoutManager(this, mColumns);
        recyclerView.setLayoutManager(mLayoutManager);

        FragmentManager fm = getSupportFragmentManager();
        mTaskFragment = (OverviewActivityTaskFragment) fm.findFragmentByTag(OverviewActivityTaskFragment.TAG);

        if (mTaskFragment == null || mTaskFragment.getExistingItems() == null) {
            mTaskFragment = new OverviewActivityTaskFragment();
            fm.beginTransaction().add(mTaskFragment, OverviewActivityTaskFragment.TAG).commit();

            mProvider.getList(mTaskFragment.getFilters(), mTaskFragment);
        } else {
            onSuccess(mTaskFragment.getExistingItems());
        }

        String action = getIntent().getAction();
        Uri data = getIntent().getData();
        if (action != null && !action.equals(Intent.ACTION_VIEW) && data != null) {
            String streamUrl = data.toString();
            try {
                streamUrl = URLDecoder.decode(streamUrl, "utf-8");
                Intent streamIntent = new Intent(this, StreamLoadingActivity.class);
                streamIntent.putExtra(StreamLoadingActivity.STREAM_URL, streamUrl);
                startActivity(streamIntent);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_overview, menu);

        MenuItem searchMenuItem = menu.findItem(R.id.action_search);
        SearchView searchViewAction = (SearchView) MenuItemCompat.getActionView(searchMenuItem);
        searchViewAction.setQueryHint("Title, year, actors"); //TODO: translation
        searchViewAction.setOnQueryTextListener(mSearchListener);
        searchViewAction.setIconifiedByDefault(true);

        MenuItem playerTestMenuItem = menu.findItem(R.id.action_playertests);
        playerTestMenuItem.setVisible(Constants.DEBUG_ENABLED);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_playertests:
                openPlayerTestDialog();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if(mLoadingDetails) {
            progressOverlay.setVisibility(View.GONE);
            mProvider.cancel();
            mLoadingDetails = false;
            return;
        }

        super.onBackPressed();
    }

    @Override
    public void onSuccess(final ArrayList<Media> items) {
        mEndOfListReached = false;
        if(mTotalItemCount <= 0) {
            mAdapter = new OverviewGridAdapter(OverviewActivity.this, items, mColumns);
            mAdapter.setOnItemClickListener(mOnItemClickListener);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    progressOverlay.setVisibility(View.GONE);
                    recyclerView.setAdapter(mAdapter);
                    recyclerView.setOnScrollListener(mScrollListener);
                    mPreviousTotal = mTotalItemCount = mAdapter.getItemCount();
                    emptyView.setVisibility(View.GONE);
                }
            });
            mLoading = false;
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAdapter.setItems(items);
                }
            });
        }
    }

    @Override
    public void onFailure(Exception e) {
        if(e.getMessage() != null && e.getMessage().equals(YTSProvider.NO_MOVIES_ERROR)) {
            mEndOfListReached = true;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mAdapter.removeLoading();

                    if(mAdapter.getItemCount() <= 0) {
                        emptyView.setVisibility(View.VISIBLE);
                    }
                }
            });
        } else {
            e.printStackTrace();
            LogUtils.e(e.getMessage());
            if (mRetries > 1) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(OverviewActivity.this, "Something went wrong", Toast.LENGTH_SHORT).show(); //TODO: translation (by sv244)
                        emptyView.setVisibility(View.VISIBLE);
                    }
                });
            } else {
                mProvider.getList(null, mTaskFragment);
            }
            mRetries++;
        }
    }

    private OverviewGridAdapter.OnItemClickListener mOnItemClickListener = new OverviewGridAdapter.OnItemClickListener() {
        @Override
        public void onItemClick(View view, final Media item, int position) {
            progressOverlay.setBackgroundColor(getResources().getColor(R.color.overlay_bg));
            progressOverlay.setVisibility(View.VISIBLE);

            mLoadingDetails = true;
            mProvider.getDetail(item.videoId, new MediaProvider.Callback() {
                @Override
                public void onSuccess(ArrayList<Media> items) {
                    if (items.size() <= 0 || !mLoadingDetails) return;
                    mLoadingDetails = false;

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            progressOverlay.setVisibility(View.GONE);
                            progressOverlay.setBackgroundColor(getResources().getColor(android.R.color.transparent));
                        }
                    });

                    final Media item = items.get(0);

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Intent intent;
                            if(item instanceof Movie) {
                                intent = new Intent(OverviewActivity.this, MovieDetailActivity.class);
                            } else {
                                intent = new Intent(OverviewActivity.this, ShowDetailActivity.class);
                            }
                            intent.putExtra("item", item);
                            startActivity(intent);
                        }
                    });
                }

                @Override
                public void onFailure(Exception e) {
                    if(!e.getMessage().equals("Canceled")) {
                        e.printStackTrace();
                        Log.e("OverviewActivity", e.getMessage());
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(OverviewActivity.this, "Something went wrong", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    mLoadingDetails = false;
                }
            });
        }
    };

    private RecyclerView.OnScrollListener mScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            mVisibleItemCount = mLayoutManager.getChildCount();
            mTotalItemCount = mLayoutManager.getItemCount();
            mFirstVisibleItem = mLayoutManager.findFirstVisibleItemPosition();

            if (mLoading) {
                if (mTotalItemCount > mPreviousTotal) {
                    mLoading = false;
                    mPreviousTotal = mTotalItemCount;
                    mAdapter.removeLoading();
                    mPreviousTotal = mTotalItemCount = mLayoutManager.getItemCount();
                }
            }

            if (!mEndOfListReached && !mLoading && (mTotalItemCount - mVisibleItemCount) <= (mFirstVisibleItem + mLoadingTreshold)) {
                HashMap<String, String> filters = mTaskFragment.getFilters();
                filters.put("page", Integer.toString(mTaskFragment.getCurrentPage()));
                mProvider.getList(mAdapter.getItems(), filters, mTaskFragment);
                mTaskFragment.setFilters(filters);
                mAdapter.addLoading();
                mPreviousTotal = mTotalItemCount = mLayoutManager.getItemCount();
                mLoading = true;
            }
        }
    };

    private SearchView.OnQueryTextListener mSearchListener = new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String s) {
            mEndOfListReached = false;
            if(mAdapter != null) {
                mAdapter.clearItems();
                mAdapter.addLoading();
            }
            HashMap<String, String> filters = mTaskFragment.getFilters();
            if(s.equals("")) {
                filters.remove("keywords");
            } else {
                filters.put("keywords", s);
            }
            filters.put("page", "1");
            mTaskFragment.setCurrentPage(1);
            mTaskFragment.setFilters(filters);
            mProvider.getList(filters, mTaskFragment);
            return true;
        }

        @Override
        public boolean onQueryTextChange(String s) {
            if(s.equals("")) {
                onQueryTextSubmit(s);
            }
            return false;
        }
    };

    private void openPlayerTestDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        final String[] file_types = getResources().getStringArray(R.array.file_types);
        final String[] files = getResources().getStringArray(R.array.files);

        builder.setTitle("Player Tests")
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        dialogInterface.dismiss();
                    }
                }).setSingleChoiceItems(file_types, -1, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int index) {
                dialogInterface.dismiss();
                String location = files[index];
                if (YouTubeData.isYouTubeUrl(location)) {
                    Intent i = new Intent(OverviewActivity.this, TrailerPlayerActivity.class);
                    Media media = new Media();
                    media.title = file_types[index];
                    i.putExtra(TrailerPlayerActivity.DATA, media);
                    i.putExtra(TrailerPlayerActivity.LOCATION, location);
                    startActivity(i);
                } else {
                    Media media = new Media();
                    final Intent i = new Intent(OverviewActivity.this, VideoPlayerActivity.class);
                    i.putExtra(VideoPlayerActivity.DATA, media);
                    i.putExtra(VideoPlayerActivity.LOCATION, location);
                    media.videoId = "bigbucksbunny";
                    media.title = file_types[index];
                    media.subtitles = new HashMap<String, String>();
                    media.subtitles.put("en", "http://popcorn.sv244.cf/bbb-subs.srt");
                    SubsProvider.download(OverviewActivity.this, media, "en", new Callback() {
                        @Override
                        public void onFailure(Request request, IOException e) {
                            startActivity(i);
                        }

                        @Override
                        public void onResponse(Response response) throws IOException {
                            i.putExtra(VideoPlayerActivity.SUBTITLES, "en");
                            startActivity(i);
                        }
                    });
                }
            }
        });

        builder.show();
    }
}