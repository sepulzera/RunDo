package com.werdpressed.partisan.rundo;

import android.annotation.TargetApi;
import android.app.Fragment;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;

/**
 * Implementation of {@link RunDo} which extends {@link Fragment}. It is best to create an
 * instance of this class with {@link com.werdpressed.partisan.rundo.RunDo.Factory}, rather than
 * with {@link #newInstance()} or {@link #RunDoNative()} directly.
 *
 * @author Tom Calver
 */
@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class RunDoNative extends Fragment implements RunDo {

    private RunDo.TextLink mTextLink;
    private RunDo.Callbacks mCallbacks;

    private final Handler mHandler;
    private final WriteToArrayDequeRunnable mRunnable;
    private boolean isRunning;

    private long countdownTimerLength;
    private int queueSize;

    private CustomArrayDeque<SubtractStrings> mUndoQueue, mRedoQueue;

    private String mOldText, mNewText;
    private int trackingState;

    public RunDoNative() {
        mHandler = new Handler();
        mRunnable = new WriteToArrayDequeRunnable(this);

        countdownTimerLength = DEFAULT_TIMER_LENGTH;
        queueSize = DEFAULT_QUEUE_SIZE;

        trackingState = TRACKING_ENDED;
    }

    public static RunDoNative newInstance() {
        return new RunDoNative();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mTextLink = (RunDo.TextLink) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString() + " must implement RunDo.TextLink");
        }

        if (context instanceof RunDo.Callbacks) mCallbacks = (RunDo.Callbacks) context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (mUndoQueue == null) mUndoQueue = new CustomArrayDeque<>(queueSize);
        if (mRedoQueue == null) mRedoQueue = new CustomArrayDeque<>(queueSize);

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mTextLink.getEditText().addTextChangedListener(this);

        if (savedInstanceState != null) {
            mUndoQueue = savedInstanceState.getParcelable(UNDO_TAG);
            mRedoQueue = savedInstanceState.getParcelable(REDO_TAG);

            trackingState = TRACKING_STARTED;
        }

    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        
        outState.putParcelable(UNDO_TAG, mUndoQueue);
        outState.putParcelable(REDO_TAG, mRedoQueue);

    }

    @Override
    public void onDetach() {
        super.onDetach();
        mTextLink = null;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

        if (mOldText == null) mOldText = mTextLink.getEditText().getText().toString();

        if (trackingState == TRACKING_ENDED) {
            startCountdownRunnable();
            trackingState = TRACKING_CURRENT;
        }

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

        switch (trackingState) {
            case TRACKING_STARTED:
                trackingState = TRACKING_ENDED;
                break;
            case TRACKING_CURRENT:
                restartCountdownRunnable();
                break;
        }

    }

    @Override
    public void afterTextChanged(Editable s) {
        //Unused
    }

    /**
     *
     * @see {@link WriteToArrayDeque#getNewString()}
     */
    @Override
    public String getNewString() {
        mNewText = mTextLink.getEditText().getText().toString();
        return mNewText;
    }

    /**
     *
     * @see {@link WriteToArrayDeque#getOldString()}
     */
    @Override
    public String getOldString() {
        return mOldText;
    }

    /**
     *
     * @see {@link WriteToArrayDeque#notifyArrayDequeDataReady(SubtractStrings)}
     */
    @Override
    public void notifyArrayDequeDataReady(SubtractStrings subtractStrings) {

        mUndoQueue.addFirst(subtractStrings);

        mOldText = mTextLink.getEditText().getText().toString();

        trackingState = TRACKING_ENDED;

    }

    /**
     *
     * @see {@link WriteToArrayDeque#setIsRunning(boolean)}
     */
    @Override
    public void setIsRunning(boolean isRunning) {
        this.isRunning = isRunning;
    }

    /**
     *
     * @see {@link RunDo#setQueueSize(int)}
     */
    @Override
    public void setQueueSize(int size) {
        queueSize = size;
        mUndoQueue = new CustomArrayDeque<>(queueSize);
        mRedoQueue = new CustomArrayDeque<>(queueSize);
    }

    /**
     *
     * @see {@link RunDo#setTimerLength(long)}
     */
    @Override
    public void setTimerLength(long lengthInMillis) {
        countdownTimerLength = lengthInMillis;
    }

    /**
     *
     * @see {@link RunDo#undo()}
     */
    @Override
    public void undo() {

        trackingState = TRACKING_STARTED;

        if (isRunning) {
            restartCountdownRunnableImmediately();
            return;
        }

        if (mUndoQueue.peek() == null) {
            //Log.e(TAG, "Undo Queue Empty");
            return;
        }

        try {

            SubtractStrings temp = mUndoQueue.poll();

            switch (temp.getDeviationType()) {
                case SubtractStrings.ADDITION:
                    mTextLink.getEditText().getText().delete(
                            temp.getFirstDeviation(),
                            temp.getLastDeviationNewText()
                    );
                    break;
                case SubtractStrings.DELETION:
                    mTextLink.getEditText().getText().insert(
                            temp.getFirstDeviation(),
                            temp.getReplacedText());
                    break;
                case SubtractStrings.REPLACEMENT:
                    mTextLink.getEditText().getText().replace(
                            temp.getFirstDeviation(),
                            temp.getLastDeviationNewText(),
                            temp.getReplacedText());
                    break;
                case SubtractStrings.UNCHANGED:
                    break;
                default:
                    break;
            }

            mTextLink.getEditText().setSelection(temp.getFirstDeviation());

            mRedoQueue.addFirst(temp);

            if (mCallbacks != null) mCallbacks.undoCalled();

        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        } finally {
            mOldText = mTextLink.getEditText().getText().toString();
        }

    }

    /**
     *
     * @see {@link RunDo#redo()}
     */
    @Override
    public void redo() {

        trackingState = TRACKING_STARTED;

        if (mRedoQueue.peek() == null) {
            //Log.e(TAG, "Redo Queue Empty");
            return;
        }

        try {

            SubtractStrings temp = mRedoQueue.poll();

            switch (temp.getDeviationType()) {
                case SubtractStrings.ADDITION:
                    mTextLink.getEditText().getText().insert(
                            temp.getFirstDeviation(),
                            temp.getAlteredText());
                    break;
                case SubtractStrings.DELETION:
                    mTextLink.getEditText().getText().delete(
                            temp.getFirstDeviation(),
                            temp.getLastDeviationOldText()
                    );
                    break;
                case SubtractStrings.REPLACEMENT:
                    mTextLink.getEditText().getText().replace(
                            temp.getFirstDeviation(),
                            temp.getLastDeviationOldText(),
                            temp.getAlteredText());
                    break;
                case SubtractStrings.UNCHANGED:
                    break;
                default:
                    break;

            }

            mTextLink.getEditText().setSelection(temp.getFirstDeviation());

            mUndoQueue.addFirst(temp);

            if (mCallbacks != null) mCallbacks.redoCalled();

        } catch (IndexOutOfBoundsException e) {
            e.printStackTrace();
        } finally {
            mOldText = mTextLink.getEditText().getText().toString();
        }

    }

    /**
     *
     * @see {@link RunDo#clearAllQueues()}
     */
    @Override
    public void clearAllQueues() {
        mUndoQueue.clear();
        mRedoQueue.clear();
    }

    private void restartCountdownRunnableImmediately() {
        stopCountdownRunnable();
        mHandler.post(mRunnable);
    }

    private void startCountdownRunnable() {
        isRunning = true;
        mHandler.postDelayed(mRunnable, countdownTimerLength);
    }

    private void stopCountdownRunnable() {
        mHandler.removeCallbacks(mRunnable);
        isRunning = false;
    }

    private void restartCountdownRunnable() {
        stopCountdownRunnable();
        startCountdownRunnable();
    }

}