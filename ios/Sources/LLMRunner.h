#import <Foundation/Foundation.h>
NS_ASSUME_NONNULL_BEGIN
@interface LLMRunner : NSObject
+ (void)resetCancellation;
+ (void)cancel;
+ (nullable NSDictionary<NSString *, id> *)runAtPath:(NSString *)path error:(NSError **)error NS_SWIFT_NAME(run(path:));
@end
NS_ASSUME_NONNULL_END
