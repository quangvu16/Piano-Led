# Generates res/layout/activity_player.xml and res/layout-land/activity_player.xml.
# Both share view IDs so PlayerActivity can swap them on rotation.
import sys
RES = sys.argv[1]
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:app="http://schemas.android.com/apk/res-auto"'
NL = '\n'


def back():
    return '''        <ImageButton
            android:id="@+id/btnBack"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/cd_back"
            android:scaleType="center"
            android:src="@drawable/ic_chevron_back"
            app:tint="@color/text_hi" />
'''


def title(width, weight, size):
    w = ('android:layout_width="0dp"' + NL + '            android:layout_weight="1"') if weight else ('android:layout_width="' + width + '"')
    return '''        <TextView
            android:id="@+id/txtSongTitle"
            ''' + w + '''
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="4dp"
            android:ellipsize="end"
            android:maxLines="1"
            android:textColor="@color/text_hi"
            android:textSize="''' + str(size) + '''sp"
            android:textStyle="bold" />
'''


options_btn = '''        <ImageButton
            android:id="@+id/btnOptions"
            android:layout_width="44dp"
            android:layout_height="44dp"
            android:layout_marginEnd="6dp"
            android:background="@drawable/bg_circle_dark"
            android:contentDescription="@string/opt_title"
            android:scaleType="center"
            android:src="@drawable/ic_sliders"
            app:tint="@color/text_hi" />
'''

chip = '''        <LinearLayout
            android:id="@+id/ledChip"
            android:layout_width="wrap_content"
            android:layout_height="44dp"
            android:background="@drawable/bg_chip_off"
            android:clickable="true"
            android:contentDescription="@string/cd_led"
            android:focusable="true"
            android:gravity="center_vertical"
            android:orientation="horizontal"
            android:paddingHorizontal="14dp">

            <View
                android:id="@+id/ledChipDot"
                android:layout_width="8dp"
                android:layout_height="8dp"
                android:background="@drawable/bg_dot_off" />

            <TextView
                android:id="@+id/txtLedChip"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:text="@string/lb_led_off_short"
                android:textColor="@color/text_mid"
                android:textSize="13sp"
                android:textStyle="bold" />
        </LinearLayout>
'''

progress = '''            <TextView
                android:id="@+id/txtTimeNow"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="0:00"
                android:textColor="@color/text_mid"
                android:textSize="12sp"
                android:textStyle="bold" />

            <SeekBar
                android:id="@+id/seekPosition"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:max="1000"
                android:progressBackgroundTint="@color/surface_2"
                android:progressTint="@color/text_hi"
                android:thumbTint="@color/text_hi" />

            <TextView
                android:id="@+id/txtTimeTotal"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="0:00"
                android:textColor="@color/text_mid"
                android:textSize="12sp"
                android:textStyle="bold" />
'''

loopbar = '''    <com.ledpiano.app.ui.LoopRangeBar
        android:id="@+id/loopBar"
        android:layout_width="match_parent"
        android:layout_height="30dp"
        android:layout_marginHorizontal="8dp"
        android:contentDescription="@string/cd_loop_range"
        android:visibility="gone" />
'''


def staff(h):
    return '''<com.ledpiano.app.ui.StaffView
        android:id="@+id/staff"
        android:layout_width="match_parent"
        android:layout_height="''' + h + '''"
        android:visibility="gone" />'''


def roll_frame(extra):
    return '''<FrameLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        ''' + extra + '''android:layout_weight="1">

        <com.ledpiano.app.ui.PianoRollView
            android:id="@+id/pianoRoll"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />

        <TextView
            android:id="@+id/txtMode"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|start"
            android:layout_margin="8dp"
            android:background="@drawable/bg_chip_off"
            android:paddingHorizontal="10dp"
            android:paddingVertical="4dp"
            android:textColor="@color/text_hi"
            android:textSize="12sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <TextView
            android:id="@+id/txtFeedback"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="top|end"
            android:layout_margin="8dp"
            android:background="@drawable/bg_chip_off"
            android:paddingHorizontal="10dp"
            android:paddingVertical="4dp"
            android:textColor="@color/text_hi"
            android:textSize="12sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <TextView
            android:id="@+id/txtOverlay"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:gravity="center"
            android:paddingHorizontal="16dp"
            android:shadowColor="#CC000000"
            android:shadowRadius="8"
            android:textColor="@color/text_hi"
            android:textSize="22sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <ImageButton
            android:id="@+id/btnShowControls"
            android:layout_width="44dp"
            android:layout_height="64dp"
            android:layout_gravity="end|center_vertical"
            android:background="@drawable/bg_chip_off"
            android:contentDescription="@string/cd_show_controls"
            android:rotation="180"
            android:scaleType="center"
            android:src="@drawable/ic_chevron_back"
            android:visibility="gone"
            app:tint="@color/text_hi" />
    </FrameLayout>'''


def seg_hand(id_, dot, label, h, ts):
    d = 8 if ts < 15 else 10
    m = 4 if ts < 15 else 7
    return f'''                <LinearLayout
                    android:id="@+id/{id_}"
                    android:layout_width="0dp"
                    android:layout_height="{h}dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_segment_item"
                    android:clickable="true"
                    android:focusable="true"
                    android:gravity="center"
                    android:orientation="horizontal">

                    <View
                        android:layout_width="{d}dp"
                        android:layout_height="{d}dp"
                        android:background="@drawable/{dot}" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginStart="{m}dp"
                        android:duplicateParentState="true"
                        android:maxLines="1"
                        android:text="@string/{label}"
                        android:textColor="@color/seg_text"
                        android:textSize="{ts}sp"
                        android:textStyle="bold" />
                </LinearLayout>
'''


def track(inner, pad, weighted):
    size = ('android:layout_width="0dp"' + NL + '                android:layout_weight="1"') if weighted else 'android:layout_width="match_parent"'
    return '''<LinearLayout
                ''' + size + '''
                android:layout_height="wrap_content"
                android:background="@drawable/bg_segment_track"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:padding="''' + str(pad) + '''dp">

''' + inner + '''            </LinearLayout>'''


def hands(h, ts):
    return seg_hand('btnHandLeft', 'bg_dot_left', 'bt_left_short', h, ts) + seg_hand('btnHandBoth', 'bg_dot_both', 'bt_both_hands', h, ts) + seg_hand('btnHandRight', 'bg_dot_right', 'bt_right_short', h, ts)


def speed_stepper(h, ts):
    return f'''                <TextView
                    android:id="@+id/btnSpeedDown"
                    android:layout_width="0dp"
                    android:layout_height="{h}dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_segment_item"
                    android:clickable="true"
                    android:contentDescription="@string/cd_slower"
                    android:focusable="true"
                    android:gravity="center"
                    android:text="−"
                    android:textColor="@color/seg_text"
                    android:textSize="20sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/txtSpeed"
                    android:layout_width="0dp"
                    android:layout_height="{h}dp"
                    android:layout_weight="1.4"
                    android:gravity="center"
                    android:text="100%"
                    android:textColor="@color/text_hi"
                    android:textSize="{ts}sp"
                    android:textStyle="bold" />

                <TextView
                    android:id="@+id/btnSpeedUp"
                    android:layout_width="0dp"
                    android:layout_height="{h}dp"
                    android:layout_weight="1"
                    android:background="@drawable/bg_segment_item"
                    android:clickable="true"
                    android:contentDescription="@string/cd_faster"
                    android:focusable="true"
                    android:gravity="center"
                    android:text="+"
                    android:textColor="@color/seg_text"
                    android:textSize="20sp"
                    android:textStyle="bold" />
'''


def circle(id_, size, bg, icon, tint, cd, pad):
    return f'''<ImageButton
                    android:id="@+id/{id_}"
                    android:layout_width="{size}dp"
                    android:layout_height="{size}dp"
                    android:background="@drawable/{bg}"
                    android:contentDescription="@string/{cd}"
                    android:padding="{pad}dp"
                    android:scaleType="fitCenter"
                    android:src="@drawable/{icon}"
                    app:tint="@color/{tint}" />'''


def caption(text, id_=None, ts=12):
    idl = ('android:id="@+id/' + id_ + '"' + NL + '                    ') if id_ else ''
    return '''<TextView
                    ''' + idl + '''android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:text="@string/''' + text + '''"
                    android:textColor="@color/text_mid"
                    android:textSize="''' + str(ts) + '''sp"
                    android:textStyle="bold" />'''


def label(text, extra=''):
    width = 'wrap_content' if extra else '52dp'
    return '''<TextView
                android:layout_width="''' + width + '''"
                android:layout_height="wrap_content"
                ''' + extra + '''android:text="@string/''' + text + '''"
                android:textColor="@color/text_mid"
                android:textSize="13sp"
                android:textStyle="bold" />'''


P_HANDS = track(hands(40, 15), 4, True)
P_SPEED = track(speed_stepper(40, 16), 4, True)
L_HANDS = track(hands(40, 13), 3, False)
L_SPEED = track(speed_stepper(40, 15), 3, False)
MB1 = 'android:layout_marginBottom="4dp"' + NL + '                '
MB2 = 'android:layout_marginTop="10dp"' + NL + '                android:layout_marginBottom="4dp"' + NL + '                '

portrait = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Practice screen, portrait. Landscape version: layout-land/activity_player.xml.
     Both files use the same view IDs so PlayerActivity can swap them on rotation.
     Generated layout (see the redesign notes); edit both files together. -->
<LinearLayout {NS}
    android:id="@+id/playerRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/ink"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="60dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="4dp"
        android:paddingEnd="12dp">

{back()}{title(None, True, 18)}{options_btn}{chip}    </LinearLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="32dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingHorizontal="16dp">

{progress}    </LinearLayout>

{loopbar}
    {staff('120dp')}

    {roll_frame('android:layout_marginTop="4dp"' + NL + '        ')}

    <com.ledpiano.app.ui.KeyboardView
        android:id="@+id/keyboard"
        android:layout_width="match_parent"
        android:layout_height="150dp" />

    <LinearLayout
        android:id="@+id/controlsPanel"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@color/surface_1"
        android:orientation="vertical"
        android:paddingHorizontal="16dp"
        android:paddingTop="16dp"
        android:paddingBottom="18dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            {label('player_hands')}

            {P_HANDS}
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            {label('lb_speed')}

            {P_SPEED}
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:gravity="top|center_horizontal"
            android:orientation="horizontal">

            <LinearLayout
                android:layout_width="72dp"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                {circle('btnRestart', 52, 'bg_circle_dark', 'ic_restart', 'text_hi', 'bt_restart', 14)}

                {caption('bt_restart')}
            </LinearLayout>

            <LinearLayout
                android:layout_width="96dp"
                android:layout_height="wrap_content"
                android:layout_marginHorizontal="20dp"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                {circle('btnPlayPause', 72, 'bg_circle_light', 'ic_play', 'ink', 'lb_play', 18)}

                {caption('lb_play', 'txtPlayLabel')}
            </LinearLayout>

            <LinearLayout
                android:layout_width="72dp"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:gravity="center_horizontal"
                android:orientation="vertical">

                {circle('btnRepeat', 52, 'bg_circle_toggle', 'ic_repeat', 'toggle_icon', 'bt_repeat', 14)}

                {caption('bt_repeat')}
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
</LinearLayout>
'''

landscape = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Practice screen, landscape: wider keyboard on the left, controls in a rail on the right.
     Generated layout; edit together with layout/activity_player.xml. -->
<LinearLayout {NS}
    android:id="@+id/playerRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/ink"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="52dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="4dp"
        android:paddingEnd="12dp">

{back()}{title('170dp', False, 17)}        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_marginHorizontal="8dp"
            android:layout_weight="1"
            android:gravity="center_vertical"
            android:orientation="horizontal">

{progress}        </LinearLayout>

{options_btn}{chip}    </LinearLayout>

{loopbar}
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="horizontal">

        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:orientation="vertical">

            {staff('88dp')}

            {roll_frame('')}

            <com.ledpiano.app.ui.KeyboardView
                android:id="@+id/keyboard"
                android:layout_width="match_parent"
                android:layout_height="112dp" />
        </LinearLayout>

        <LinearLayout
            android:id="@+id/controlsPanel"
            android:layout_width="204dp"
            android:layout_height="match_parent"
            android:background="@color/surface_1"
            android:orientation="vertical"
            android:padding="12dp">

            {label('player_hands', MB1)}

            {L_HANDS}

            {label('lb_speed', MB2)}

            {L_SPEED}

            <Space
                android:layout_width="1dp"
                android:layout_height="0dp"
                android:layout_weight="1" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_vertical"
                android:orientation="horizontal">

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:gravity="center_horizontal"
                    android:orientation="vertical">

                {circle('btnRestart', 46, 'bg_circle_dark', 'ic_restart', 'text_hi', 'bt_restart', 12)}

                {caption('bt_restart', ts=11)}
                </LinearLayout>

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1.3"
                    android:gravity="center_horizontal"
                    android:orientation="vertical">

                {circle('btnPlayPause', 68, 'bg_circle_light', 'ic_play', 'ink', 'lb_play', 17)}

                {caption('lb_play', 'txtPlayLabel', ts=11)}
                </LinearLayout>

                <LinearLayout
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:gravity="center_horizontal"
                    android:orientation="vertical">

                {circle('btnRepeat', 46, 'bg_circle_toggle', 'ic_repeat', 'toggle_icon', 'bt_repeat', 12)}

                {caption('bt_repeat', ts=11)}
                </LinearLayout>
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
</LinearLayout>
'''

open(RES + '/layout/activity_player.xml', 'w').write(portrait)
open(RES + '/layout-land/activity_player.xml', 'w').write(landscape)
print('ok')
