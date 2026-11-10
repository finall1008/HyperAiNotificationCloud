package io.github.finall1008.hyperainotif;

import android.content.Context;
import android.content.IntentFilter;
import android.os.Message;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModule;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "HyperAiNotifCloud";
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static volatile Object sHyperNotifService;
    private static volatile SummaryScheduler sScheduler;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Loaded in process: " + param.getProcessName() + " (isSystemServer=" + param.isSystemServer() + ")");
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        hookSystemServer(param.getClassLoader());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;
        String pkg = param.getPackageName();
        if ("com.miui.notification".equals(pkg)) {
            hookMiuiNotification(param.getDefaultClassLoader());
        } else if ("com.android.settings".equals(pkg)) {
            hookSettings(param.getDefaultClassLoader());
        }
    }

    private void hookMiuiNotification(ClassLoader cl) {
        try {
            Class<?> sysUtilsClass = cl.loadClass("miui.notification.common.util.SystemUtils");

            // 1. getModelStatus("llmtask", context) -> always return Boolean.TRUE
            // Prevents checkAiModelState() from resetting ai_notification_setting to 0 on page entry,
            // and makes checkNeedDownloadModel() immediately turn on the switch!
            Method getModelStatus = sysUtilsClass.getDeclaredMethod("getModelStatus", String.class, Context.class);
            hook(getModelStatus).intercept(chain -> Boolean.TRUE);

            // 2. isMiAccountLogin(context) -> always return true
            Method isMiAccountLogin = sysUtilsClass.getDeclaredMethod("isMiAccountLogin", Context.class);
            hook(isMiAccountLogin).intercept(chain -> Boolean.TRUE);

            // 3. checkAndDownloadModel(...) -> no-op
            Method checkAndDownloadModel = sysUtilsClass.getDeclaredMethod(
                    "checkAndDownloadModel",
                    String.class, String.class, String.class, int.class, boolean.class, Context.class);
            hook(checkAndDownloadModel).intercept(chain -> null);

            log(Log.INFO, TAG, "Hooked com.miui.notification SystemUtils successfully");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook com.miui.notification", t);
        }
    }

    private void hookSettings(ClassLoader cl) {
        try {
            Class<?> notifSettingsClass = cl.loadClass("com.android.settings.NotificationStatusBarSettings");
            Method getAiPreSettings = notifSettingsClass.getDeclaredMethod("getAIPreSettings");
            hook(getAiPreSettings).intercept(chain -> Boolean.TRUE);
            log(Log.INFO, TAG, "Hooked com.android.settings NotificationStatusBarSettings.getAIPreSettings");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook com.android.settings", t);
        }
    }

    private void hookSystemServer(ClassLoader cl) {
        try {
            Class<?> hyperServiceClass = cl.loadClass("com.android.server.notification.HyperNotificationManagerService");
            Class<?> notifRecordClass = cl.loadClass("com.android.server.notification.NotificationRecord");
            Class<?> nmsImplClass = cl.loadClass("com.android.server.notification.NotificationManagerServiceImpl");

            // Capture the native service; its notification queue and summary rendering remain in use.
            Constructor<?> ctor = hyperServiceClass.getDeclaredConstructor(Context.class);
            Field notifEnabledField = findField(hyperServiceClass, "mNotificationEnabled");
            Field handlerField = findField(hyperServiceClass, "mHandler");
            Method receiveNotificationMethod = hyperServiceClass.getDeclaredMethod("receiveNotification", Intent.class);
            receiveNotificationMethod.setAccessible(true);
            Method getContextMethod = findMethod(hyperServiceClass, "getContext");
            Method getSbnMethod = notifRecordClass.getDeclaredMethod("getSbn");
            getSbnMethod.setAccessible(true);

            // Force isHighPowerDevices() -> true so HyperNotificationManagerService uses phone AI switch logic
            Method isHighPowerMethod = hyperServiceClass.getDeclaredMethod("isHighPowerDevices");
            isHighPowerMethod.setAccessible(true);
            hook(isHighPowerMethod).intercept(chain -> Boolean.TRUE);

            hook(ctor).intercept(chain -> {
                Object res = chain.proceed();
                Object self = chain.getThisObject();
                sHyperNotifService = self;
                log(Log.INFO, TAG, "Captured HyperNotificationManagerService instance");
                return res;
            });

            // 3. Hook updateNotificationInternal
            Method updateNotifInternal = hyperServiceClass.getDeclaredMethod(
                    "updateNotificationInternal", notifRecordClass, boolean.class);
            updateNotifInternal.setAccessible(true);
            Field lockField = findField(hyperServiceClass, "mNotificationLock");
            Method findNotifByRecordMethod = hyperServiceClass.getDeclaredMethod(
                    "findNotificationByListLocked", notifRecordClass);
            findNotifByRecordMethod.setAccessible(true);
            Class<?> hyperAiNotifClass = cl.loadClass("com.android.server.notification.HyperAINotification");
            Method addNotifLockedMethod = hyperServiceClass.getDeclaredMethod(
                    "addNotificationLocked", hyperAiNotifClass);
            addNotifLockedMethod.setAccessible(true);
            Method cancelNotifLockedMethod = hyperServiceClass.getDeclaredMethod(
                    "cancelNotificationLocked", hyperAiNotifClass);
            cancelNotifLockedMethod.setAccessible(true);

            Constructor<?> hyperAiNotifCtor = hyperAiNotifClass.getDeclaredConstructor(
                    notifRecordClass, String.class, String.class);
            hyperAiNotifCtor.setAccessible(true);
            Method getInputIdMethod = hyperAiNotifClass.getDeclaredMethod("getInputID");
            getInputIdMethod.setAccessible(true);
            Method getInputTextMethod = hyperAiNotifClass.getDeclaredMethod("getInputText");
            getInputTextMethod.setAccessible(true);
            Method getCategoryMethod = hyperAiNotifClass.getDeclaredMethod("getCategory");
            getCategoryMethod.setAccessible(true);
            Method setIsSendMethod = hyperAiNotifClass.getDeclaredMethod("setIsSend", boolean.class);
            setIsSendMethod.setAccessible(true);
            Method getIsSendMethod = hyperAiNotifClass.getDeclaredMethod("getIsSend");
            getIsSendMethod.setAccessible(true);
            Field queueField = findField(hyperServiceClass, "mEnqueuedNotifications");
            Field allowedPackagesField = findField(hyperServiceClass, "mAllowAINotificaitonPackagers");

            Method initMethod = hyperServiceClass.getDeclaredMethod("init", Context.class);
            initMethod.setAccessible(true);
            hook(initMethod).intercept(chain -> {
                Object self = chain.getThisObject();
                Context ctx = (Context) chain.getArg(0);
                android.provider.Settings.System.putInt(
                        ctx.getContentResolver(), "ai_notification_setting", 1);
                Object result = chain.proceed();
                sHyperNotifService = self;
                notifEnabledField.setBoolean(self, true);
                Handler handler = (Handler) handlerField.get(self);
                sScheduler = new SummaryScheduler(ctx, handler, () -> queryModuleConfig(ctx), () -> {
                    ArrayList<String> ids = new ArrayList<>();
                    ArrayList<String> messages = new ArrayList<>();
                    ArrayList<String> categories = new ArrayList<>();
                    try {
                        synchronized (lockField.get(self)) {
                            ArrayList<?> queue = (ArrayList<?>) queueField.get(self);
                            for (Object notification : queue) {
                                if ((Boolean) getIsSendMethod.invoke(notification)) continue;
                                ids.add((String) getInputIdMethod.invoke(notification));
                                messages.add((String) getInputTextMethod.invoke(notification));
                                categories.add((String) getCategoryMethod.invoke(notification));
                                setIsSendMethod.invoke(notification, true);
                            }
                        }
                        if (!ids.isEmpty()) {
                            log(Log.INFO, TAG, "Dispatching cloud summary for " + ids.size() + " notification(s)");
                            dispatchCloudSummarize(ctx, self, handlerField, receiveNotificationMethod,
                                    String.valueOf(System.currentTimeMillis()), ids, messages, categories);
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "Failed to collect pending notifications", t);
                    }
                }, () -> {
                    try {
                        synchronized (lockField.get(self)) {
                            int count = 0;
                            for (Object notification : (ArrayList<?>) queueField.get(self)) {
                                if (!(Boolean) getIsSendMethod.invoke(notification)) count++;
                            }
                            return count;
                        }
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "Failed to count pending notifications", t);
                        return 0;
                    }
                });
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Config.ACTION_CONFIG_CHANGED);
                ctx.registerReceiver(sScheduler, filter, null, handler, Context.RECEIVER_EXPORTED);
                return result;
            });

            // Replace the native screen-event/alarm path so it cannot reset sent notifications
            // or bypass the independently configured threshold and delay.
            Class<?> receiverClass = cl.loadClass("com.android.server.notification.HyperNotificationManagerService$1");
            Method onReceive = receiverClass.getDeclaredMethod("onReceive", Context.class, Intent.class);
            hook(onReceive).intercept(chain -> {
                String action = ((Intent) chain.getArg(1)).getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)
                        || "ai.HyperNotification.alarm.TIMER".equals(action)) {
                    if (sScheduler != null) sScheduler.refresh();
                    return null;
                }
                return chain.proceed();
            });
            Class<?> workerClass = cl.loadClass("com.android.server.notification.HyperNotificationManagerService$WorkerHandler");
            Method handleMessage = workerClass.getDeclaredMethod("handleMessage", Message.class);
            hook(handleMessage).intercept(chain -> {
                if (((Message) chain.getArg(0)).what == 1) {
                    if (sScheduler != null) sScheduler.refresh();
                    return null;
                }
                return chain.proceed();
            });

            hook(updateNotifInternal).intercept(chain -> {
                Object self = chain.getThisObject();
                sHyperNotifService = self;
                Object record = chain.getArg(0);
                boolean post = (Boolean) chain.getArg(1);

                Context ctx = (Context) getContextMethod.invoke(self);
                Bundle cfg = queryModuleConfig(ctx);
                boolean allApps = cfg == null || cfg.getBoolean(Config.KEY_ALL_APPS, true);

                Object lock = lockField.get(self);
                android.service.notification.StatusBarNotification sbn =
                        (android.service.notification.StatusBarNotification) getSbnMethod.invoke(record);
                String pkg = sbn.getPackageName();
                Map<?, ?> allowedPackages = (Map<?, ?>) allowedPackagesField.get(null);
                if (post && !allApps && !allowedPackages.containsKey(pkg)) return null;
                String category = allowedPackages.containsKey(pkg) ? (String) allowedPackages.get(pkg) : "1_IM";

                synchronized (lock) {
                    if (post) {
                        Object existing = findNotifByRecordMethod.invoke(self, record);
                        if (existing != null) {
                            cancelNotifLockedMethod.invoke(self, existing);
                        }
                        Object aiNotif = hyperAiNotifCtor.newInstance(record, pkg, category);
                        addNotifLockedMethod.invoke(self, aiNotif);

                        try {
                            if (android.provider.Settings.System.getInt(
                                    ctx.getContentResolver(), "ai_notification_setting", 0) != 1) {
                                android.provider.Settings.System.putInt(
                                        ctx.getContentResolver(), "ai_notification_setting", 1);
                            }
                        } catch (Throwable ignored) {}
                        if (notifEnabledField != null) {
                            notifEnabledField.setBoolean(self, true);
                        }
                    } else {
                        Object existing = findNotifByRecordMethod.invoke(self, record);
                        if (existing != null) {
                            cancelNotifLockedMethod.invoke(self, existing);
                        }
                    }
                }
                if (sScheduler != null) sScheduler.refresh();
                return null;
            });

            // Hook NotificationManagerServiceImpl.replaceNotification to support configurable min length
            Method replaceNotifMethod = nmsImplClass.getDeclaredMethod("replaceNotification", notifRecordClass, boolean.class);
            replaceNotifMethod.setAccessible(true);

            hook(replaceNotifMethod).intercept(chain -> {
                Object record = chain.getArg(0);
                boolean flag = (Boolean) chain.getArg(1);

                try {
                    android.service.notification.StatusBarNotification sbn =
                            (android.service.notification.StatusBarNotification) getSbnMethod.invoke(record);
                    if (sbn == null) return null;
                    android.app.Notification n = sbn.getNotification();
                    if (n == null || n.extras == null) return null;

                    // Skip if already summarized (0x10000000), group summary (0x200), ongoing (0x2), foreground (0x40), or 0x08000000
                    if ((n.flags & 0x08000000) != 0) return null;
                    if ((n.flags & 0x10000000) != 0) return null;
                    if ((n.flags & 0x40) != 0) return null;
                    if ((n.flags & 0x2) != 0) return null;
                    if ((n.flags & 0x200) != 0) return null;

                    CharSequence cs = n.extras.getCharSequence("android.text");
                    if (cs == null) return null;
                    String text = cs.toString().trim();
                    if (text.isEmpty()) return null;

                    int minLen = Config.DEFAULT_MIN_LENGTH;
                    Object hyperSvc = sHyperNotifService;
                    if (hyperSvc != null) {
                        Context ctx = (Context) getContextMethod.invoke(hyperSvc);
                        Bundle cfg = queryModuleConfig(ctx);
                        if (cfg != null) {
                            minLen = cfg.getInt(Config.KEY_MIN_LENGTH, Config.DEFAULT_MIN_LENGTH);
                        }
                        if (text.length() > minLen) {
                            updateNotifInternal.invoke(hyperSvc, record, flag);
                        }
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Error in replaceNotification hook", t);
                }
                return null;
            });

            log(Log.INFO, TAG, "Hooked system_server notification queue and configurable summary scheduling");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook system_server", t);
        }
    }

    private void dispatchCloudSummarize(
            Context ctx,
            Object hyperService,
            Field handlerField,
            Method receiveNotificationMethod,
            String session,
            ArrayList<String> inputIds,
            ArrayList<String> messages,
            ArrayList<String> categories) {
        EXECUTOR.execute(() -> {
            try {
                Bundle extras = new Bundle();
                extras.putStringArrayList("messageList", messages);
                extras.putStringArrayList("category", categories);

                Bundle reply = ctx.getContentResolver().call(
                        Uri.parse("content://" + Config.AUTHORITY),
                        "summarize",
                        null,
                        extras);

                if (reply == null || !"0".equals(reply.getString("status"))) {
                    log(Log.WARN, TAG, "Cloud summarize returned non-zero status");
                    return;
                }

                ArrayList<String> outputs = reply.getStringArrayList("outputtext_list");
                ArrayList<String> errorCodes = reply.getStringArrayList("errorcode_list");

                Intent callbackIntent = new Intent("com.xiaomi.aicr.llm.MESSAGE_SUMMARIZE_CALLBACK");
                callbackIntent.putExtra("message_summarize_session", session);
                callbackIntent.putExtra("message_summarize_status", "0");
                callbackIntent.putStringArrayListExtra("message_summarize_inputid_list", inputIds);
                callbackIntent.putStringArrayListExtra("message_summarize_errorcode_list", errorCodes);
                callbackIntent.putStringArrayListExtra("message_summarize_outputtext_list", outputs);

                Handler h = (Handler) handlerField.get(hyperService);
                if (h != null) {
                    h.post(() -> {
                        try {
                            receiveNotificationMethod.invoke(hyperService, callbackIntent);
                            log(Log.INFO, TAG, "Applied cloud AI summary to " + inputIds.size() + " notification(s)");
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "Failed to invoke receiveNotification", t);
                        }
                    });
                } else {
                    receiveNotificationMethod.invoke(hyperService, callbackIntent);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "dispatchCloudSummarize failed", t);
            }
        });
    }

    private Bundle queryModuleConfig(Context ctx) {
        try {
            return ctx.getContentResolver().call(
                    Uri.parse("content://" + Config.AUTHORITY),
                    "get_config",
                    null,
                    null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> curr = cls;
        while (curr != null) {
            try {
                Field f = curr.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        Class<?> curr = cls;
        while (curr != null) {
            try {
                Method m = curr.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                curr = curr.getSuperclass();
            }
        }
        return null;
    }
}
