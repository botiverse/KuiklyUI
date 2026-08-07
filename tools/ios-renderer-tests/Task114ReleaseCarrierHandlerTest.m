#import <UIKit/UIKit.h>

#import "KuiklyRenderLayerHandler.h"
#import "KRRichTextView.h"

@interface Task114ReleaseCarrierAppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end

@implementation Task114ReleaseCarrierAppDelegate

- (BOOL)application:(UIApplication *)application
    didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController = [UIViewController new];
    [self.window makeKeyAndVisible];

    // The production handler resolves this view by string. Keep an explicit
    // class reference so the static renderer archive member is linked into
    // the fixture app instead of being discarded as apparently unused.
    Class richTextViewClass = [KRRichTextView class];
    NSCAssert(
        richTextViewClass != Nil && [NSStringFromClass(richTextViewClass) isEqualToString:@"KRRichTextView"],
        @"KRRichTextView must be present in the carrier fixture binary"
    );

    KuiklyContextParam *contextParam =
        [KuiklyContextParam newWithPageName:@"task114-release-carrier"
                          resourceFolderUrl:nil];
    KuiklyRenderLayerHandler *handler =
        [[KuiklyRenderLayerHandler alloc] initWithRootView:self.window contextParam:contextParam];
    [handler createRenderViewWithTag:@114 viewName:@"KRRichTextView"];
    [handler callViewMethodWithTag:@114
                           method:@"accessibilityFocus"
                           params:nil
                         callback:nil];

    NSString *receipt =
        [NSTemporaryDirectory() stringByAppendingPathComponent:@"task114-carrier-pass"];
    NSError *error = nil;
    BOOL wrote = [@"optional-view-method-safe" writeToFile:receipt
                                                   atomically:YES
                                                     encoding:NSUTF8StringEncoding
                                                        error:&error];
    NSCAssert(wrote && error == nil, @"failed to write task114 carrier receipt: %@", error);
    return YES;
}

@end

int main(int argc, char *argv[]) {
    @autoreleasepool {
        return UIApplicationMain(
            argc,
            argv,
            nil,
            NSStringFromClass(Task114ReleaseCarrierAppDelegate.class)
        );
    }
}
