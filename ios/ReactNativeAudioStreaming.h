// AudioManager.h
// From https://github.com/jhabdas/lumpen-radio/blob/master/iOS/Classes/AudioManager.h

#import <React/RCTBridgeModule.h>
#import "STKAudioPlayer.h"

@import MediaPlayer;

@interface ReactNativeAudioStreaming : NSObject <RCTBridgeModule, STKAudioPlayerDelegate>

@property (nonatomic, strong) STKAudioPlayer *audioPlayer;
@property (nonatomic, readwrite) BOOL isPlayingWithOthers;
@property (nonatomic, readwrite) BOOL showNowPlayingInfo;
@property (nonatomic, readwrite) NSString *lastUrlString;
@property (nonatomic, retain) NSString *currentSong;
@property (nonatomic, readwrite) NSString *artWorkURL;
@property (nonatomic, readwrite) MPMediaItemArtwork *artWork;
@property (nonatomic, readwrite) NSString *currentStation;
@property (nonatomic, readwrite) BOOL isNextPrevCmdSent;

- (void)play:(NSString *) streamUrl options:(NSDictionary *)options;
- (void)pause;

@end
