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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import java.util.List;

public class BrowserBenchActivity extends Activity
{
    private static final String LOGTAG = "BrowserBench";

    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private LayoutInflater mInflater;
    private Spinner mSpinner;

    private Thread mMonitorThread;
    private Handler mMonitorHandler;
    private MemoryUsageRunnable mMonitorRunnable;

    private Handler mUIHandler;

    private Button mStartStopButton;
    private boolean mRunning;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mActivityManager = (ActivityManager)getSystemService(Activity.ACTIVITY_SERVICE);
        mPackageManager = getPackageManager();
        mInflater = (LayoutInflater)getSystemService(Activity.LAYOUT_INFLATER_SERVICE);

        mSpinner = (Spinner)findViewById(R.id.browserSpinner);
        populateBrowserSpinner();

        mStartStopButton = (Button)findViewById(R.id.startStopButton);
        mStartStopButton.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startOrStop();
            }
        });

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

    private void startOrStop() {
        if (mRunning) {
            mRunning = false;
            mStartStopButton.setText("Start");

            if (mMonitorRunnable != null) {
                mMonitorRunnable.stop();
                mMonitorRunnable = null;
            }
        } else {
            mRunning = true;
            mStartStopButton.setText("Stop");

            launch("http://cnn.com");
        }
    }

    private void populateBrowserSpinner() {
        List<ResolveInfo> result = mPackageManager.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost")), 0);

        mSpinner.setAdapter(new BrowserSpinnerAdapter(result));
    }

    private void launch(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

        ResolveInfo info = (ResolveInfo)mSpinner.getSelectedItem();

        Log.i(LOGTAG, "expect process name: " + info.activityInfo.applicationInfo.processName);

        i.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);

        mMonitorRunnable = new MemoryUsageRunnable(info.activityInfo.applicationInfo.processName);
        mMonitorHandler.post(mMonitorRunnable);
    }

    private void updateUsageLabels(int min, int max, int current) {
        ((TextView)findViewById(R.id.minUsageText)).setText(Integer.toString(min));
        ((TextView)findViewById(R.id.maxUsageText)).setText(Integer.toString(max));
        ((TextView)findViewById(R.id.currentUsageText)).setText(Integer.toString(current));
    }

    private class MemoryUsageRunnable implements Runnable
    {
        private String mProcessName;
        private ActivityManager.RunningAppProcessInfo mAppInfo;
        private boolean mStopped;

        public int mMaxUsage;
        public int mMinUsage;
        public int mLastUsage;

        public MemoryUsageRunnable(String processName) {
            mProcessName = processName;
        }

        public void stop() {
            Log.i(LOGTAG, "stopping monitoring");
            mStopped = true;
        }

        private ActivityManager.RunningAppProcessInfo waitForApp() {
            for (;;) {
                List<ActivityManager.RunningAppProcessInfo> procs = mActivityManager.getRunningAppProcesses();
                for (ActivityManager.RunningAppProcessInfo procInfo : procs) {
                    if (procInfo.processName.equals(mProcessName)) {
                        return procInfo;
                    }
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {}
            }
        }

        public void run() {
            if (mStopped)
                return;

            if (mAppInfo == null)
                mAppInfo = waitForApp();

            Log.i(LOGTAG, "polling pid " + mAppInfo.pid);
            int[] pids = new int[1];
            pids[0] = mAppInfo.pid;

            MemoryInfo[] infos = mActivityManager.getProcessMemoryInfo(pids);

            if (infos.length == 0) {
                stop();
                return;
            }

            int current = infos[0].getTotalPrivateDirty();
            if (mMinUsage == 0 || current < mMinUsage)
                mMinUsage = current;
            if (mMaxUsage == 0 || current > mMaxUsage)
                mMaxUsage = current;

            mLastUsage = current;


            mUIHandler.post(new Runnable() {
                public void run() {
                    updateUsageLabels(mMinUsage, mMaxUsage, mLastUsage);
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

            ((ImageView)view.findViewById(R.id.spinnerLogo)).setImageDrawable(info.loadIcon(mPackageManager));
            ((TextView)view.findViewById(R.id.spinnerLabel)).setText(info.loadLabel(mPackageManager));
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
