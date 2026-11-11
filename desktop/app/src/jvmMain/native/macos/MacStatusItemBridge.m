#import <AppKit/AppKit.h>
#import <dispatch/dispatch.h>
#import <jni.h>

static NSStatusItem *statusItem;
static id statusTarget;
static jobject bridgeClass;
static JavaVM *javaVM;
static id appResignObserver;
static id rightClickMonitor;
static NSMenu *contextMenu;

@interface SkillBillMainRunner : NSObject
+ (void)run:(dispatch_block_t)block;
@end

@implementation SkillBillMainRunner
+ (void)run:(dispatch_block_t)block {
  block();
}
@end

static void onMainWaiting(dispatch_block_t block) {
  if (NSThread.isMainThread) {
    block();
    return;
  }
  dispatch_block_t copied = [block copy];
  [SkillBillMainRunner performSelectorOnMainThread:@selector(run:)
                                        withObject:copied
                                     waitUntilDone:YES
                                             modes:@[NSRunLoopCommonModes, @"AWTRunLoopMode"]];
  [copied release];
}

static void releaseBridgeClass(void) {
  if (bridgeClass == NULL || javaVM == NULL) return;
  JNIEnv *env = NULL;
  BOOL attached = NO;
  jint state = (*javaVM)->GetEnv(javaVM, (void **)&env, JNI_VERSION_1_6);
  if (state == JNI_EDETACHED && (*javaVM)->AttachCurrentThread(javaVM, (void **)&env, NULL) == JNI_OK) attached = YES;
  else if (state != JNI_OK) return;
  (*env)->DeleteGlobalRef(env, bridgeClass);
  bridgeClass = NULL;
  if (attached) (*javaVM)->DetachCurrentThread(javaVM);
}

@interface SkillBillStatusTarget : NSObject
@end

@implementation SkillBillStatusTarget
- (void)activate:(id)sender {
  JNIEnv *env = NULL;
  BOOL attached = NO;
  if (javaVM == NULL) return;
  jint state = (*javaVM)->GetEnv(javaVM, (void **)&env, JNI_VERSION_1_6);
  if (state == JNI_EDETACHED && (*javaVM)->AttachCurrentThread(javaVM, (void **)&env, NULL) == JNI_OK) attached = YES;
  else if (state != JNI_OK) return;
  if (sender == statusItem.button) {
    NSRect rect = [statusItem.button.window convertRectToScreen:statusItem.button.bounds];
    NSScreen *screen = statusItem.button.window.screen ?: NSScreen.mainScreen;
    CGFloat desktopTop = NSMaxY(NSScreen.mainScreen.frame);
    rect.origin.y = desktopTop - NSMaxY(rect);
    NSRect usable = screen.visibleFrame;
    usable.origin.y = desktopTop - NSMaxY(usable);
    jclass klass = (jclass)bridgeClass;
    jmethodID callback = (*env)->GetStaticMethodID(env, klass, "onPrimaryAction", "(DDDDDDDDD)V");
    if (callback != NULL) (*env)->CallStaticVoidMethod(env, klass, callback,
      (jdouble)rect.origin.x, (jdouble)rect.origin.y, (jdouble)rect.size.width, (jdouble)rect.size.height,
      (jdouble)usable.origin.x, (jdouble)usable.origin.y, (jdouble)usable.size.width, (jdouble)usable.size.height,
      (jdouble)screen.backingScaleFactor);
  } else {
    NSInteger action = [sender representedObject] == nil ? -1 : [[sender representedObject] integerValue];
    jclass klass = (jclass)bridgeClass;
    jmethodID callback = (*env)->GetStaticMethodID(env, klass, "onMenuAction", "(I)V");
    if (callback != NULL) (*env)->CallStaticVoidMethod(env, klass, callback, (jint)action);
  }
  if (attached) (*javaVM)->DetachCurrentThread(javaVM);
}
@end

static NSString *stringAt(JNIEnv *env, jobjectArray values, jsize index) {
  jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, index);
  if (value == NULL) return @"";
  const jchar *characters = (*env)->GetStringChars(env, value, NULL);
  if (characters == NULL) {
    (*env)->DeleteLocalRef(env, value);
    return @"";
  }
  NSString *result = [NSString stringWithCharacters:characters length:(NSUInteger)(*env)->GetStringLength(env, value)];
  (*env)->ReleaseStringChars(env, value, characters);
  (*env)->DeleteLocalRef(env, value);
  return result;
}

static NSArray *strings(JNIEnv *env, jobjectArray values) {
  NSMutableArray *result = [NSMutableArray array];
  for (jsize i = 0; i < (*env)->GetArrayLength(env, values); i++) [result addObject:stringAt(env, values, i)];
  return result;
}

static NSData *imageData(JNIEnv *env, jbyteArray bytes) {
  if (bytes == NULL) return nil;
  jsize length = (*env)->GetArrayLength(env, bytes);
  if (length <= 0) return nil;
  NSMutableData *data = [[NSMutableData alloc] initWithLength:(NSUInteger)length];
  if (data == nil) return nil;
  (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)data.mutableBytes);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
    [data release];
    return nil;
  }
  return data;
}

static NSImage *decodedImage(NSData *data) {
  if (data == nil) return nil;
  NSImage *image = [[NSImage alloc] initWithData:data];
  if (image != nil) {
    [image setTemplate:NO];
    [image setSize:NSMakeSize(18.0, 18.0)];
  }
  return image;
}

static void disposeStatusItem(void) {
  if (statusItem != nil) {
    [[NSStatusBar systemStatusBar] removeStatusItem:statusItem];
    [statusItem release];
  }
  statusItem = nil;
  if (rightClickMonitor != nil) {
    [NSEvent removeMonitor:rightClickMonitor];
    [rightClickMonitor release];
  }
  rightClickMonitor = nil;
  [contextMenu release];
  contextMenu = nil;
  [statusTarget release];
  statusTarget = nil;
  if (appResignObserver != nil) {
    [NSNotificationCenter.defaultCenter removeObserver:appResignObserver];
    [appResignObserver release];
  }
  appResignObserver = nil;
  releaseBridgeClass();
}

static NSMenu *submenu(NSArray *values, NSInteger base) {
  NSMenu *menu = [[NSMenu alloc] initWithTitle:@""];
  for (NSUInteger i = 0; i < values.count; i++) {
    NSMenuItem *entry = [[NSMenuItem alloc] initWithTitle:values[i] action:@selector(activate:) keyEquivalent:@""];
    entry.target = statusTarget;
    entry.representedObject = @(base + i);
    [menu addItem:entry];
    [entry release];
  }
  return menu;
}

JNIEXPORT jboolean JNICALL Java_dev_skillbill_desktop_app_tray_macos_MacStatusItemBridge_nativeInstall(JNIEnv *env, jclass klass, jbyteArray imageBytes) {
  (*env)->GetJavaVM(env, &javaVM);
  NSData *imageDataCopy = imageData(env, imageBytes);
  NSImage *image = decodedImage(imageDataCopy);
  [imageDataCopy release];
  if (image == nil) return JNI_FALSE;
  if (bridgeClass == NULL) bridgeClass = (*env)->NewGlobalRef(env, klass);
  __block BOOL failed = NO;
  onMainWaiting(^{
    if (statusItem != nil || bridgeClass == NULL) { failed = YES; return; }
    statusTarget = [SkillBillStatusTarget new];
    statusItem = [[[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength] retain];
    if (statusItem == nil) {
      failed = YES;
      [statusTarget release];
      statusTarget = nil;
      return;
    }
    statusItem.button.target = statusTarget;
    statusItem.button.action = @selector(activate:);
    statusItem.button.image = image;
    statusItem.button.accessibilityLabel = @"Sona";
    statusItem.button.menu = nil;
    rightClickMonitor = [[NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskRightMouseDown handler:^NSEvent *(NSEvent *event) {
      if (statusItem == nil || event.window != statusItem.button.window) return event;
      NSPoint point = [statusItem.button convertPoint:event.locationInWindow fromView:nil];
      if (!NSPointInRect(point, statusItem.button.bounds)) return event;
      [contextMenu popUpMenuPositioningItem:nil at:statusItem.button.bounds.origin inView:statusItem.button];
      return nil;
    }] retain];
    appResignObserver = [[NSNotificationCenter.defaultCenter addObserverForName:NSApplicationDidResignActiveNotification object:NSApp queue:NSOperationQueue.mainQueue usingBlock:^(__unused NSNotification *notification) {
      JNIEnv *callbackEnv = NULL;
      BOOL attached = NO;
      jint state = (*javaVM)->GetEnv(javaVM, (void **)&callbackEnv, JNI_VERSION_1_6);
      if (state == JNI_EDETACHED && (*javaVM)->AttachCurrentThread(javaVM, (void **)&callbackEnv, NULL) == JNI_OK) attached = YES;
      else if (state != JNI_OK) return;
      jclass callbackClass = (jclass)bridgeClass;
      jmethodID callback = (*callbackEnv)->GetStaticMethodID(callbackEnv, callbackClass, "onDismissPopover", "()V");
      if (callback != NULL) (*callbackEnv)->CallStaticVoidMethod(callbackEnv, callbackClass, callback);
      if (attached) (*javaVM)->DetachCurrentThread(javaVM);
    }] retain];
  });
  [image release];
  if (failed) onMainWaiting(^{ disposeStatusItem(); });
  return failed ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_dev_skillbill_desktop_app_tray_macos_MacStatusItemBridge_nativeUpdate(JNIEnv *env, jclass klass, jbyteArray imageBytes, jstring kindValue, jint count, jobjectArray goals, jobjectArray phases) {
  NSData *imageDataCopy = imageData(env, imageBytes);
  NSImage *image = decodedImage(imageDataCopy);
  [imageDataCopy release];
  if (image == nil) return JNI_FALSE;
  const char *kindChars = (*env)->GetStringUTFChars(env, kindValue, NULL);
  NSString *kind = [NSString stringWithUTF8String:kindChars ?: "idle"];
  (*env)->ReleaseStringUTFChars(env, kindValue, kindChars);
  NSArray *goalLabels = strings(env, goals);
  NSArray *phaseLabels = strings(env, phases);
  __block BOOL updated = NO;
  onMainWaiting(^{
    if (statusItem == nil) return;
    statusItem.button.image = image;
    statusItem.button.title = [kind isEqualToString:@"running"] && count > 0 ? [NSString stringWithFormat:@"%d", count] : @"";
    NSMenu *menu = [[NSMenu alloc] initWithTitle:@""];
    NSMenuItem *goalGroup = [[NSMenuItem alloc] initWithTitle:@"Goals" action:nil keyEquivalent:@""];
    NSMenu *goalsMenu = submenu(goalLabels, 0);
    goalGroup.submenu = goalsMenu;
    [goalsMenu release];
    [menu addItem:goalGroup];
    [goalGroup release];
    NSMenuItem *phaseGroup = [[NSMenuItem alloc] initWithTitle:@"Phases and operations" action:nil keyEquivalent:@""];
    NSMenu *phasesMenu = submenu(phaseLabels, 1000);
    phaseGroup.submenu = phasesMenu;
    [phasesMenu release];
    [menu addItem:phaseGroup];
    [phaseGroup release];
    [menu addItem:NSMenuItem.separatorItem];
    NSArray *labels = @[@"Open Sona", @"Refresh now", @"Settings", @"Quit"];
    NSArray *actions = @[@2000, @2002, @2003, @2004];
    for (NSInteger i = 0; i < labels.count; i++) {
      NSMenuItem *entry = [[NSMenuItem alloc] initWithTitle:labels[i] action:@selector(activate:) keyEquivalent:@""];
      entry.target = statusTarget;
      entry.representedObject = actions[i];
      [menu addItem:entry];
      [entry release];
    }
    if (contextMenu != nil) [contextMenu release];
    contextMenu = [menu retain];
    [menu release];
    updated = YES;
  });
  [image release];
  return updated ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_skillbill_desktop_app_tray_macos_MacStatusItemBridge_nativeDispose(JNIEnv *env, jclass klass) {
  onMainWaiting(^{ disposeStatusItem(); });
}
