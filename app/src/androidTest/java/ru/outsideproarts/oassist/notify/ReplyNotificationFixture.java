package ru.outsideproarts.oassist.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;

/** Runs in the test APK's own UID, using only the Android/Java runtime. */
public final class ReplyNotificationFixture extends BroadcastReceiver {
    public static final String CHANNEL = "ad007_reply_fixture";
    public static final String RESULT_KEY = "reply_text";
    public static final int ID = 7007;
    private static ResultReceiver replies;

    @Override
    public void onReceive(Context context, Intent intent) {
        String operation = intent.getAction();
        ResultReceiver completion = intent.getParcelableExtra("completion");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        try {
            if ("reply".equals(operation)) {
                Bundle input = RemoteInput.getResultsFromIntent(intent);
                Bundle output = new Bundle();
                output.putString("text", input == null ? null : input.getCharSequence(RESULT_KEY).toString());
                if (replies != null) replies.send(2, output);
                return;
            }
            if ("clear".equals(operation)) {
                manager.cancel(CHANNEL, ID);
                manager.deleteNotificationChannel(CHANNEL);
                replies = null;
            } else if ("post".equals(operation)) {
                replies = intent.getParcelableExtra("replies");
                manager.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "AD-007 reply fixture", NotificationManager.IMPORTANCE_LOW));
                Intent reply = new Intent(context, ReplyNotificationFixture.class).setAction("reply");
                PendingIntent pending = PendingIntent.getBroadcast(context, ID, reply,
                        PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);
                Notification.Action action = new Notification.Action.Builder(
                        android.R.drawable.ic_menu_send, "Reply", pending)
                        .addRemoteInput(new RemoteInput.Builder(RESULT_KEY).setLabel("Reply").build())
                        .build();
                manager.notify(CHANNEL, ID, new Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("AD-007 reply fixture")
                        .setContentText("Instrumented contract test")
                        .addAction(action).build());
                if (intent.getBooleanExtra("cancelPendingIntent", false)) pending.cancel();
            } else {
                throw new IllegalArgumentException("Unknown fixture operation");
            }
            if (completion != null) completion.send(1, Bundle.EMPTY);
        } catch (Exception error) {
            Bundle result = new Bundle();
            result.putString("error", error.toString());
            if (completion != null) completion.send(-1, result);
        }
    }
}
