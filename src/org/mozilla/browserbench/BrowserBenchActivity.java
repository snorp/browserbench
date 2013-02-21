package org.mozilla.browserbench;

import android.app.Activity;
import android.app.ActivityManager;

import android.content.Context;

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import android.content.Intent;

import android.database.DataSetObserver;

import android.net.Uri;

import android.os.Bundle;
import android.os.Debug.MemoryInfo;
import android.os.Handler;
import android.os.Looper;

import android.util.Log;

import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class BrowserBenchActivity extends Activity
{
    private static final String LOGTAG = "BrowserBench";
    private static final String START_URL = "http://people.mozilla.org/~jwillcox/browserbench/bench.html";
    private static final String[] BENCHMARK_URLS = { "http://mozilla.org", "http://cnn.com", "http://engadget.com", "http://wikipedia.com" };

    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private LayoutInflater mInflater;
    private Spinner mSpinner;

    private View mResultsList;

    private Thread mMonitorThread;
    private Handler mMonitorHandler;
    private MemoryUsageRunnable mMonitorRunnable;

    private Handler mUIHandler;

    private Button mStartStopButton;
    private boolean mRunning;

    private ResolveInfo mBrowserInfo;
    private int mNumTabs;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mActivityManager = (ActivityManager)getSystemService(Activity.ACTIVITY_SERVICE);
        mPackageManager = getPackageManager();
        mInflater = (LayoutInflater)getSystemService(Activity.LAYOUT_INFLATER_SERVICE);

        mSpinner = (Spinner)findViewById(R.id.spinner_browser);
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                ResolveInfo info = (ResolveInfo)mSpinner.getItemAtPosition(pos);
                if (!info.activityInfo.applicationInfo.packageName.equals(mBrowserInfo.activityInfo.applicationInfo.packageName)) {
                    stop();
                    mBrowserInfo = info;
                }
            }

            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        populateBrowserSpinner();
        mBrowserInfo = (ResolveInfo)mSpinner.getSelectedItem();

        mStartStopButton = (Button)findViewById(R.id.button_start_stop);
        mStartStopButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startOrStop();
            }
        });

        mResultsList = findViewById(R.id.container_results);

        mUIHandler = new Handler();

        mMonitorThread = new Thread(new Runnable() {
            public void run() {
                Looper.prepare();

                mMonitorHandler = new Handler();

                Looper.loop();
            }
        });
        mMonitorThread.start();
    }

    public void onTabCountSelected(View view) {
        switch (view.getId()) {
            case R.id.radio_no_tabs:
                mNumTabs = 0;
                break;
            case R.id.radio_one_tab:
                mNumTabs = 1;
                break;
            case R.id.radio_many_tabs:
                mNumTabs = BENCHMARK_URLS.length;
                break;
        }
    }

    private void startOrStop() {
        if (mRunning) {
            stop();
        } else {
            start();
        }
    }

    private void start() {
        if (mRunning)
            return;

        mRunning = true;
        mStartStopButton.setText("Stop");

        launch();

        mResultsList.setVisibility(View.VISIBLE);
    }

    private void stop() {
        if (!mRunning)
            return;

        mRunning = false;
        mStartStopButton.setText("Start");

        if (mMonitorRunnable != null) {
            mMonitorRunnable.stop();
            mMonitorRunnable = null;
        }

        mResultsList.setVisibility(View.GONE);
    }

    private void populateBrowserSpinner() {
        List<ResolveInfo> result = mPackageManager.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost")), 0);

        mSpinner.setAdapter(new BrowserSpinnerAdapter(result));
    }

    private void launch() {
        Intent intent = new Intent(Intent.ACTION_VIEW);

        mActivityManager.killBackgroundProcesses(mBrowserInfo.activityInfo.applicationInfo.packageName);

        intent.setClassName(mBrowserInfo.activityInfo.packageName, mBrowserInfo.activityInfo.name);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        mMonitorRunnable = new MemoryUsageRunnable(mBrowserInfo.activityInfo.applicationInfo.processName,
                                                   mBrowserInfo.activityInfo.applicationInfo.packageName);
        mMonitorHandler.post(mMonitorRunnable);

        Log.i(LOGTAG, "SNORP: launching with tabs: " + mNumTabs);
        if (mNumTabs == 0) {
            startActivity(intent);
        } else {
            for (int i = 0; i < mNumTabs; i++) {
                intent.setData(Uri.parse(BENCHMARK_URLS[i]));
                startActivity(intent);
            }
        }
        
    }

    private String formatBytes(int kb) {
        return (kb / 1024) + " MB";
    }

    private void updateUsageLabels(int min, int max, int current) {
        ((TextView)findViewById(R.id.text_min_usage)).setText(formatBytes(min));
        ((TextView)findViewById(R.id.text_max_usage)).setText(formatBytes(max));
        ((TextView)findViewById(R.id.text_current_usage)).setText(formatBytes(current));
    }

    private class MemoryUsageRunnable implements Runnable
    {
        private String mProcessName;
        private String mPackageName;
        private boolean mStopped;

        public int mMaxUsage;
        public int mMinUsage;
        public int mLastTotal;

        public MemoryUsageRunnable(String processName, String packageName) {
            mProcessName = processName;
            mPackageName = packageName;
        }

        public void stop() {
            Log.i(LOGTAG, "stopping monitoring");
            mStopped = true;
        }

        private int[] findAppProcesses() {
            ArrayList<Integer> pids = new ArrayList<Integer>();

            List<ActivityManager.RunningAppProcessInfo> procs = mActivityManager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo procInfo : procs) {
                if (procInfo.processName.startsWith(mProcessName) || procInfo.processName.startsWith(mPackageName))
                    pids.add(procInfo.pid);
            }

            int[] result = new int[pids.size()];

            // Good lord. Really?
            for (int i = 0; i < pids.size(); i++) {
                result[i] = pids.get(i);
            }

            return result;
        }

        public void run() {
            if (mStopped)
                return;

            int[] pids = findAppProcesses();

            Log.i(LOGTAG, "SNORP: found " + pids.length + " processes for " + mPackageName);
            MemoryInfo[] infos = mActivityManager.getProcessMemoryInfo(pids);

            int currentTotal = 0;

            for (MemoryInfo info : infos) {
                currentTotal += info.getTotalPss();
            }

            if (mMinUsage == 0 || currentTotal < mMinUsage)
                mMinUsage = currentTotal;
            if (mMaxUsage == 0 || currentTotal > mMaxUsage)
                mMaxUsage = currentTotal;

            mLastTotal = currentTotal;

            mUIHandler.post(new Runnable() {
                public void run() {
                    updateUsageLabels(mMinUsage, mMaxUsage, mLastTotal);
                }
            });

            mMonitorHandler.postDelayed(this, 1000);
        }
    }

    private class BrowserSpinnerAdapter implements SpinnerAdapter
    {
        private List<ResolveInfo> mActivities;

        public BrowserSpinnerAdapter(List<ResolveInfo> activities) {
            mActivities = activities;
        }

        public int getCount() {
            return mActivities.size();
        }

        public boolean isEmpty() {
            return mActivities.isEmpty();
        }

        public Object getItem(int position) {
            try {
                return mActivities.get(position);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }

        public long getItemId(int position) {
            return 0;
        }

        public int getItemViewType(int position) {
            return Adapter.IGNORE_ITEM_VIEW_TYPE;
        }

        public int getViewTypeCount() {
            return 1;
        }

        public boolean hasStableIds() { 
            return false;
        }

        public void registerDataSetObserver(DataSetObserver observer) {

        }

        public void unregisterDataSetObserver(DataSetObserver observer) {

        }

        private View inflateView(int position) {
            return mInflater.inflate(R.layout.browserspinner, null);
        }

        private void fillView(int position, View view) {
            ResolveInfo info = (ResolveInfo)getItem(position);
            if (info == null)
                return;

            ((ImageView)view.findViewById(R.id.image_logo)).setImageDrawable(info.loadIcon(mPackageManager));
            ((TextView)view.findViewById(R.id.text_label)).setText(info.loadLabel(mPackageManager));
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null)
                v = inflateView(position);

            fillView(position, v);
            return v;
        }

        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return getView(position, convertView, parent);
        }
    }
}
