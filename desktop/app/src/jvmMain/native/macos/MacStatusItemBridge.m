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

static void onMain(dispatch_block_t block) {
  if (NSThread.isMainThread) block();
  else dispatch_sync(dispatch_get_main_queue(), block);
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

JNIEXPORT jboolean JNICALL Java_dev_skillbill_desktop_app_tray_macos_MacStatusItemBridge_nativeInstall(JNIEnv *env, jclass klass) {
  (*env)->GetJavaVM(env, &javaVM);
  if (bridgeClass == NULL) bridgeClass = (*env)->NewGlobalRef(env, klass);
  __block BOOL failed = NO;
  onMain(^{
    if (statusItem != nil) return;
    statusTarget = [SkillBillStatusTarget new];
    statusItem = [[[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength] retain];
    if (statusItem == nil) {
      failed = YES;
      return;
    }
    statusItem.button.target = statusTarget;
    statusItem.button.action = @selector(activate:);
    NSImage *image = [[NSImage imageWithSystemSymbolName:@"checkmark.circle" accessibilityDescription:@"Skill Bill"] copy];
    [image setTemplate:YES];
    statusItem.button.image = image;
    [image release];
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
  return failed ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT void JNICALL Java_dev_skillbill_desktop_app_tray_macos_MacStatusItemBridge_nativeUpdate(JNIEnv *env, jclass klass, jstring kindValue, jint count, jobjectArray goals, jobjectArray phases) {
  const char *kindChars = (*env)->GetStringUTFChars(env, kindValue, NULL);
  NSString *kind = [NSString stringWithUTF8String:kindChars ?: "idle"];
  (*env)->ReleaseStringUTFChars(env, kindValue, kindChars);
  NSArray *goalLabels = strings(env, goals);
  NSArray *phaseLabels = strings(env, phases);
  onMain(^{
    if (statusItem == nil) return;
    NSString *symbol = [kind isEqualToString:@"failed"] ? @"exclamationmark.triangle" : ([kind isEqualToString:@"needs_you"] ? @"bell.badge" : ([kind isEqualToString:@"running"] ? @"bolt" : @"checkmark.circle"));
    NSImage *image = [[NSImage imageWithSystemSymbolName:symbol accessibilityDescription:@"Skill Bill"] copy];
    [image setTemplate:YES];
    statusItem.button.image = image;
    [image release];
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
    NSArray *labels = @[@"Open Skill Bill", @"Refresh now", @"Settings", @"Quit"];
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
  });
}

JNIEXPORT void JNICALL Java_dev_skillbill_desktop_app_tray_macos_MacStatusItemBridge_nativeDispose(JNIEnv *env, jclass klass) {
  onMain(^{
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
  });
  if (bridgeClass != NULL) {
    (*env)->DeleteGlobalRef(env, bridgeClass);
    bridgeClass = NULL;
  }
}
