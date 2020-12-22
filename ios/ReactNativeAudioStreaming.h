#import <React/RCTBridgeModule.h>
#import <STKAudioPlayer.h>
#import <STKAutoRecoveringHTTPDataSource.h>

@import MediaPlayer;

@interface ReactNativeAudioStreaming : NSObject <RCTBridgeModule, STKAudioPlayerDelegate>

@property (nonatomic, strong) STKAutoRecoveringHTTPDataSource *dataSource;
@property (nonatomic, strong) STKAudioPlayer *audioPlayer;
@property (nonatomic, readwrite) BOOL isPlayingWithOthers;
@property (nonatomic, readwrite) BOOL showNowPlayingInfo;
@property (nonatomic, readwrite) NSString *logo;
@property (nonatomic, readwrite) NSString *lastUrlString;
@property (nonatomic, retain) NSString *currentSong;
@property (nonatomic, readwrite) NSString *artWorkURL;
@property (nonatomic, readwrite) MPMediaItemArtwork *artWork;
@property (nonatomic, readwrite) NSString *currentStation;
@property (nonatomic, readwrite) BOOL isNextPrevCmdSent;
@property (nonatomic, readwrite) NSTimeInterval lastPlayPauseCall;

- (void)play:(NSArray *)streams playIndex:(int)playIndex metaUrl:(NSString *)metaUrl options:(NSDictionary *)options;
- (void)pause;

@end
