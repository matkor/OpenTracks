/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.apps.mytracks;

import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import com.google.android.apps.mytracks.content.ContentProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.fragments.ChooseActivityTypeDialogFragment;
import com.google.android.apps.mytracks.fragments.ChooseActivityTypeDialogFragment.ChooseActivityTypeCaller;
import com.google.android.apps.mytracks.services.TrackRecordingServiceConnection;
import com.google.android.apps.mytracks.util.TrackIconUtils;
import com.google.android.apps.mytracks.util.TrackRecordingServiceConnectionUtils;
import com.google.android.apps.mytracks.util.TrackUtils;
import com.google.android.maps.mytracks.R;

/**
 * An activity that let's the user see and edit the user editable track meta
 * data such as track name, activity type, and track description.
 *
 * @author Leif Hendrik Wilden
 */
public class TrackEditActivity extends AbstractActivity implements ChooseActivityTypeCaller {

    public static final String EXTRA_TRACK_ID = "track_id";
    public static final String EXTRA_NEW_TRACK = "new_track";

    private static final String TAG = TrackEditActivity.class.getSimpleName();
    private static final String ICON_VALUE_KEY = "icon_value_key";

    private TrackRecordingServiceConnection trackRecordingServiceConnection;
    private ContentProviderUtils contentProviderUtils;
    private Track track;
    private String iconValue;

    private EditText name;
    private AutoCompleteTextView activityType;
    private Spinner activityTypeIcon;
    private EditText description;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        trackRecordingServiceConnection = new TrackRecordingServiceConnection(this, null);
        Long trackId = getIntent().getLongExtra(EXTRA_TRACK_ID, -1L);
        if (trackId == -1L) {
            Log.e(TAG, "invalid trackId");
            finish();
            return;
        }

        contentProviderUtils = ContentProviderUtils.Factory.get(this);
        track = contentProviderUtils.getTrack(trackId);
        if (track == null) {
            Log.e(TAG, "No track for " + trackId);
            finish();
            return;
        }

        name = findViewById(R.id.track_edit_name);
        name.setText(track.getName());

        activityType = findViewById(R.id.track_edit_activity_type);
        activityType.setText(track.getCategory());

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.activity_types, android.R.layout.simple_dropdown_item_1line);
        activityType.setAdapter(adapter);
        activityType.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                setActivityTypeIcon(TrackIconUtils.getIconValue(
                        TrackEditActivity.this, (String) activityType.getAdapter().getItem(position)));
            }
        });
        activityType.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    setActivityTypeIcon(TrackIconUtils.getIconValue(
                            TrackEditActivity.this, activityType.getText().toString()));
                }
            }
        });

        iconValue = null;
        if (bundle != null) {
            iconValue = bundle.getString(ICON_VALUE_KEY);
        }
        if (iconValue == null) {
            iconValue = track.getIcon();
        }

        activityTypeIcon = findViewById(R.id.track_edit_activity_type_icon);
        activityTypeIcon.setAdapter(TrackIconUtils.getIconSpinnerAdapter(this, iconValue));
        activityTypeIcon.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    ChooseActivityTypeDialogFragment.newInstance(activityType.getText().toString()).show(
                            getSupportFragmentManager(),
                            ChooseActivityTypeDialogFragment.CHOOSE_ACTIVITY_TYPE_DIALOG_TAG);
                }
                return true;
            }
        });
        activityTypeIcon.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    ChooseActivityTypeDialogFragment.newInstance(activityType.getText().toString()).show(
                            getSupportFragmentManager(),
                            ChooseActivityTypeDialogFragment.CHOOSE_ACTIVITY_TYPE_DIALOG_TAG);
                }
                return true;
            }
        });

        description = findViewById(R.id.track_edit_description);
        description.setText(track.getDescription());

        Button save = findViewById(R.id.track_edit_save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TrackUtils.updateTrack(TrackEditActivity.this, track, name.getText().toString(),
                        activityType.getText().toString(), description.getText().toString(),
                        contentProviderUtils);
                finish();
            }
        });

        Button cancel = findViewById(R.id.track_edit_cancel);
        if (getIntent().getBooleanExtra(EXTRA_NEW_TRACK, false)) {
            setTitle(R.string.track_edit_new_track_title);
            cancel.setVisibility(View.GONE);
        } else {
            setTitle(R.string.menu_edit);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
            cancel.setVisibility(View.VISIBLE);
        }
    }


    @Override
    protected void onStart() {
        super.onStart();
        TrackRecordingServiceConnectionUtils.startConnection(this, trackRecordingServiceConnection);
    }

    @Override
    protected void onStop() {
        super.onStop();
        trackRecordingServiceConnection.unbind();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ICON_VALUE_KEY, iconValue);
    }

    @Override
    protected int getLayoutResId() {
        return R.layout.track_edit;
    }

    private void setActivityTypeIcon(String value) {
        iconValue = value;
        TrackIconUtils.setIconSpinner(activityTypeIcon, value);
    }

    @Override
    public void onChooseActivityTypeDone(String value) {
        setActivityTypeIcon(value);
        activityType.setText(getString(TrackIconUtils.getIconActivityType(value)));
    }
}