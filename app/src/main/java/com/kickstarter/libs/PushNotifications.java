package com.kickstarter.libs;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.ContextCompat;
import android.util.Pair;

import com.kickstarter.R;
import com.kickstarter.libs.qualifiers.ApplicationContext;
import com.kickstarter.libs.rx.transformers.Transformers;
import com.kickstarter.libs.transformations.CircleTransformation;
import com.kickstarter.libs.transformations.CropSquareTransformation;
import com.kickstarter.libs.utils.ObjectUtils;
import com.kickstarter.models.Update;
import com.kickstarter.models.pushdata.Activity;
import com.kickstarter.models.pushdata.GCM;
import com.kickstarter.services.ApiClientType;
import com.kickstarter.services.apiresponses.PushNotificationEnvelope;
import com.kickstarter.ui.IntentKey;
import com.kickstarter.ui.activities.ProjectActivity;
import com.kickstarter.ui.activities.WebViewActivity;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.RequestCreator;

import java.io.IOException;

import rx.Observable;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.CompositeSubscription;
import timber.log.Timber;

public final class PushNotifications {
  private final @ApplicationContext Context context;
  private final ApiClientType client;
  private final DeviceRegistrarType deviceRegistrar;

  private final PublishSubject<PushNotificationEnvelope> notifications = PublishSubject.create();
  private final CompositeSubscription subscriptions = new CompositeSubscription();

  public PushNotifications(final @ApplicationContext @NonNull Context context, final @NonNull ApiClientType client,
    final @NonNull DeviceRegistrarType deviceRegistrar) {

    this.context = context;
    this.client = client;
    this.deviceRegistrar = deviceRegistrar;
  }

  public void initialize() {
    subscriptions.add(notifications
      .onBackpressureBuffer()
      .filter(PushNotificationEnvelope::isFriendFollow)
      .observeOn(Schedulers.newThread())
      .subscribe(this::displayNotificationFromFriendFollowActivity));

    subscriptions.add(notifications
      .onBackpressureBuffer()
      .filter(PushNotificationEnvelope::isProjectActivity)
      .observeOn(Schedulers.newThread())
      .subscribe(this::displayNotificationFromProjectActivity));

    subscriptions.add(notifications
      .onBackpressureBuffer()
      .filter(PushNotificationEnvelope::isProjectReminder)
      .observeOn(Schedulers.newThread())
      .subscribe(this::displayNotificationFromProjectReminder));

    subscriptions.add(notifications
      .onBackpressureBuffer()
      .filter(PushNotificationEnvelope::isProjectUpdateActivity)
      .flatMap(this::fetchUpdateWithEnvelope)
      .filter(ObjectUtils::isNotNull)
      .observeOn(Schedulers.newThread())
      .subscribe(envelopeAndUpdate -> displayNotificationFromUpdateActivity(envelopeAndUpdate.first, envelopeAndUpdate.second)));

    deviceRegistrar.registerDevice();
  }

  public void add(final @NonNull PushNotificationEnvelope envelope) {
    notifications.onNext(envelope);
  }

  private void displayNotificationFromFriendFollowActivity(final @NonNull PushNotificationEnvelope envelope) {
    final GCM gcm = envelope.gcm();

    final Activity activity = envelope.activity();
    if (activity == null) {
      return;
    }

    final Notification notification = notificationBuilder(gcm.title(), gcm.alert())
      .setLargeIcon(fetchBitmap(activity.userPhoto(), true))
      .build();
    notificationManager().notify(envelope.signature(), notification);
  }

  private void displayNotificationFromProjectActivity(final @NonNull PushNotificationEnvelope envelope) {
    final GCM gcm = envelope.gcm();

    final Activity activity = envelope.activity();
    if (activity == null) {
      return;
    }
    final Long projectId = activity.projectId();
    if (projectId == null) {
      return;
    }
    final String projectPhoto = activity.projectPhoto();

    final String projectParam = ObjectUtils.toString(projectId);

    NotificationCompat.Builder notificationBuilder = notificationBuilder(gcm.title(), gcm.alert())
      .setContentIntent(projectContentIntent(envelope, projectParam));
    if (projectPhoto != null) {
      notificationBuilder = notificationBuilder.setLargeIcon(fetchBitmap(projectPhoto, false));
    }
    final Notification notification = notificationBuilder.build();

    notificationManager().notify(envelope.signature(), notification);
  }

  private void displayNotificationFromProjectReminder(final @NonNull PushNotificationEnvelope envelope) {
    final GCM gcm = envelope.gcm();

    final PushNotificationEnvelope.Project project = envelope.project();
    if (project == null) {
      return;
    }

    final Notification notification = notificationBuilder(gcm.title(), gcm.alert())
      .setContentIntent(projectContentIntent(envelope, ObjectUtils.toString(project.id())))
      .setLargeIcon(fetchBitmap(project.photo(), false))
      .build();

    notificationManager().notify(envelope.signature(), notification);
  }

  private void displayNotificationFromUpdateActivity(final @NonNull PushNotificationEnvelope envelope, final @NonNull Update update) {
    final GCM gcm = envelope.gcm();

    final Activity activity = envelope.activity();
    if (activity == null) {
      return;
    }
    final Long updateId = activity.updateId();
    if (updateId == null) {
      return;
    }
    final Long projectId = activity.projectId();
    if (projectId == null) {
      return;
    }

    final String projectParam = ObjectUtils.toString(projectId);

    final Notification notification = notificationBuilder(gcm.title(), gcm.alert())
      .setContentIntent(projectUpdateContentIntent(envelope, update, projectParam))
      .setLargeIcon(fetchBitmap(activity.projectPhoto(), false))
      .build();
    notificationManager().notify(envelope.signature(), notification);
  }

  private @NonNull NotificationCompat.Builder notificationBuilder(final @NonNull String title, final @NonNull String text) {
    return new NotificationCompat.Builder(context)
      .setSmallIcon(R.drawable.ic_witinda_k)
      .setColor(ContextCompat.getColor(context, R.color.green))
      .setContentText(text)
      .setContentTitle(title)
      .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
      .setAutoCancel(true);
  }

  private @NonNull PendingIntent projectContentIntent(final @NonNull PushNotificationEnvelope envelope, final @NonNull String projectParam) {
    final Intent projectIntent = new Intent(context, ProjectActivity.class)
      .putExtra(IntentKey.PROJECT_PARAM, projectParam)
      .putExtra(IntentKey.PUSH_NOTIFICATION_ENVELOPE, envelope);

    final TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(context)
      .addNextIntentWithParentStack(projectIntent);

    return taskStackBuilder.getPendingIntent(envelope.signature(), PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private @NonNull PendingIntent projectUpdateContentIntent(final @NonNull PushNotificationEnvelope envelope, final @NonNull Update update, final @NonNull String projectParam) {

    final Intent projectIntent = new Intent(context, ProjectActivity.class)
      .putExtra(IntentKey.PROJECT_PARAM, projectParam);

    final Intent updateIntent = new Intent(context, WebViewActivity.class)
      .putExtra(IntentKey.URL, update.urls().web().update())
      .putExtra(IntentKey.PUSH_NOTIFICATION_ENVELOPE, envelope);

    final TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(context)
      .addNextIntentWithParentStack(projectIntent)
      .addNextIntent(updateIntent);

    return taskStackBuilder.getPendingIntent(envelope.signature(), PendingIntent.FLAG_UPDATE_CURRENT);
  }

  private @Nullable Bitmap fetchBitmap(final @Nullable String url, final boolean transformIntoCircle) {
    if (url == null) {
      return null;
    }

    try {
      RequestCreator requestCreator = Picasso.with(context).load(url).transform(new CropSquareTransformation());
      if (transformIntoCircle) {
        requestCreator = requestCreator.transform(new CircleTransformation());
      }
      return requestCreator.get();
    } catch (IOException e) {
      Timber.e("Failed to load large icon: %s",  e);
      return null;
    }
  }

  private @NonNull NotificationManager notificationManager() {
    return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
  }

  private @Nullable Observable<Pair<PushNotificationEnvelope, Update>> fetchUpdateWithEnvelope(final @NonNull PushNotificationEnvelope envelope) {
    final Activity activity = envelope.activity();
    if (activity == null) {
      return null;
    }

    final Long updateId = activity.updateId();
    if (updateId == null) {
      return null;
    }

    final Long projectId = activity.projectId();
    if (projectId == null) {
      return null;
    }

    final String projectParam = ObjectUtils.toString(projectId);
    final String updateParam = ObjectUtils.toString(updateId);

    final Observable<Update> update = client.fetchUpdate(projectParam, updateParam)
      .compose(Transformers.neverError());

    return Observable.just(envelope)
      .compose(Transformers.combineLatestPair(update));
  }
}
