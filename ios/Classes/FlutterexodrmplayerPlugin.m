#import "FlutterexodrmplayerPlugin.h"
#if __has_include(<flutterexodrmplayer/flutterexodrmplayer-Swift.h>)
#import <flutterexodrmplayer/flutterexodrmplayer-Swift.h>
#else
// Support project import fallback if the generated compatibility header
// is not copied when this plugin is created as a library.
// https://forums.swift.org/t/swift-static-libraries-dont-copy-generated-objective-c-header/19816
#import "flutterexodrmplayer-Swift.h"
#endif

@implementation FlutterexodrmplayerPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
  [SwiftFlutterexodrmplayerPlugin registerWithRegistrar:registrar];
}
@end
