#import <React/RCTBridgeModule.h>
#import <React/RCTEventDispatcher.h>
#import <STKAudioPlayer.h>

#import <ReactNativeAudioStreaming/ReactNativeAudioStreaming.h>
#import <ReactNativeAudioStreaming/ReactNativeAudioStreaming-Swift.h>

#define LPN_AUDIO_BUFFER_SEC 20 // Can't use this with shoutcast buffer meta data
#define TICK_INTERVAL 5.0
#define SWITCH_TRACK_AFTER_TRIES 3
#define HEADSET_PLAY @"HEADSET_PLAY"
@import AVFoundation;
@import MediaPlayer;

@interface ReactNativeAudioStreaming() {
@private
   SInt64 lastPosition;
   NSArray *currentStreams;
   bool isAutoQuality;
   int currentPlayIndex;
   NSString *metaurl;
   NSTimeInterval playbackStartTime;
   int needSwitchTries;
   bool isPlaying;
   MetaParser *metaparser;
   NSString *stationFirebaseId;
   NSTimeInterval playerStart;
}

-(void)play:(int)playIndex;

@end

@implementation ReactNativeAudioStreaming

@synthesize bridge = _bridge;

RCT_EXPORT_MODULE()
- (dispatch_queue_t)methodQueue
{
   return dispatch_get_main_queue();
}

- (ReactNativeAudioStreaming *)init
{
   /*
    if (options->bufferSizeInSeconds == 0)
    {
    options->bufferSizeInSeconds = STK_DEFAULT_PCM_BUFFER_SIZE_IN_SECONDS;
    }

    if (options->readBufferSize == 0)
    {
    options->readBufferSize = STK_DEFAULT_READ_BUFFER_SIZE;
    }

    if (options->secondsRequiredToStartPlaying == 0)
    {
    options->secondsRequiredToStartPlaying = MIN(STK_DEFAULT_SECONDS_REQUIRED_TO_START_PLAYING, options->bufferSizeInSeconds);
    }

    if (options->secondsRequiredToStartPlayingAfterBufferUnderun == 0)
    {
    options->secondsRequiredToStartPlayingAfterBufferUnderun = MIN(STK_DEFAULT_SECONDS_REQUIRED_TO_START_PLAYING_AFTER_BUFFER_UNDERRUN, options->bufferSizeInSeconds);
    }

    if (options->gracePeriodAfterSeekInSeconds == 0)
    {
    options->gracePeriodAfterSeekInSeconds = MIN(STK_DEFAULT_GRACE_PERIOD_AFTER_SEEK_SECONDS, options->bufferSizeInSeconds);
    }
    */
   self = [super init];
   if (self) {
      [self setSharedAudioSessionCategory];
      self.audioPlayer = [[STKAudioPlayer alloc] initWithOptions:(STKAudioPlayerOptions){
         .flushQueueOnSeek = YES,
         .bufferSizeInSeconds = 200,
         .readBufferSize = 1024*256,
         .secondsRequiredToStartPlaying = 2.5
      }];

      metaparser = [[MetaParser alloc] init];
      isPlaying = false;
      [self.audioPlayer setDelegate:self];
      self.lastUrlString = @"";
      self.artWorkURL = @"";
      self.artWork = nil;
      self.currentStation = @"";
      [NSTimer scheduledTimerWithTimeInterval:TICK_INTERVAL target:self selector:@selector(tick:) userInfo:nil repeats:YES];
      self.isNextPrevCmdSent = NO;
      self.lastPlayPauseCall = 0;
      NSLog(@"AudioPlayer initialized");

      [self registerRemoteControlEvents];
   }

   return self;
}


-(void) tick:(NSTimer*)timer
{
   if (!self.audioPlayer) {
      return;
   }
   //autoquality
   SInt64 position = [self.dataSource.innerDataSource getPositionBytes];
   double bitrate = (position - lastPosition)/TICK_INTERVAL;
   NSLog([NSString stringWithFormat:@"Bitrate %.20lf", bitrate]);
   lastPosition = position;

   [metaparser fetch:metaurl];
   if(metaparser.lastArtUrl == nil) {
      metaparser.lastArtUrl = self.logo;
   }
   if(metaparser.changed) {
      [self nowPlayInfo:metaparser.lastArtUrl station:self.currentStation songName:metaparser.lastSongName];
   }

   if(!isAutoQuality || bitrate < 01) {
      return;
   }

   if (self.audioPlayer.currentlyPlayingQueueItemId != nil && self.audioPlayer.state != STKAudioPlayerStatePlaying && self.audioPlayer.state != STKAudioPlayerStateBuffering) {
      return;
   }


   double currentBitrate = [[currentStreams[currentPlayIndex] objectForKey:@"bitrate"] doubleValue] / 8; //bytes/sec
   NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
   if(now - playbackStartTime < 5 * TICK_INTERVAL) {
      return;
   }

   int newIndex = 0;
   for(int i=0; i<[currentStreams count]; i++) {
       double streamBitrate = [[currentStreams[i] objectForKey:@"bitrate"] doubleValue] / 8;
      if(streamBitrate < bitrate) {
         newIndex = i;
      }
   }

   //play another stream
   if(newIndex != currentPlayIndex) {
      if(self.audioPlayer.state == STKAudioPlayerStatePlaying && needSwitchTries < SWITCH_TRACK_AFTER_TRIES) {
         needSwitchTries++;
         return;
      }

      currentPlayIndex = newIndex;

      [self play:currentPlayIndex];

      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                      @"status": @"TRACK_CHANGED",
                                                                                      @"bitrate": [currentStreams[currentPlayIndex] objectForKey:@"bitrate"],
                                                                                      }];

      NSLog([NSString stringWithFormat:@"Bitrate switched to index %d", currentPlayIndex]);
   } else {
      needSwitchTries = 0;
   }

/*   if (self.audioPlayer.currentlyPlayingQueueItemId != nil && self.audioPlayer.state == STKAudioPlayerStatePlaying) {
      NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];
      NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
      NSString *url = [NSString stringWithString:self.audioPlayer.currentlyPlayingQueueItemId];

      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                 @"status": @"STREAMING",
                                                                                 @"progress": progress,
                                                                                 @"duration": duration,
                                                                                 @"url": url,
                                                                                 }];
   }*/
}

- (void)nextTrack {
   if (!self.audioPlayer) {
      return;
   }
   if (self.audioPlayer.currentlyPlayingQueueItemId != nil) {
      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                    @"remoteCommand": @"NEXT",                                                                            }];
   }
}
- (void)prevTrack {
   if (!self.audioPlayer) {
      return;
   }
   if (self.audioPlayer.currentlyPlayingQueueItemId != nil) {
      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                   @"remoteCommand": @"PREVIOUS",                                                                            }];
   }
}
- (void)dealloc
{
   [self unregisterAudioInterruptionNotifications];
   [self unregisterRemoteControlEvents];
   [self.audioPlayer setDelegate:nil];
}


#pragma mark - Pubic API

RCT_EXPORT_METHOD(play:(NSArray *)streams playIndex:(int)playIndex metaUrl:(NSString *)metaUrl options:(NSDictionary *)options)
{
   if (!self.audioPlayer || !streams) {
      return;
   }

   [self activate];
   currentStreams = streams;
   isAutoQuality = false;
   if(playIndex < 0) {
      playIndex = 1;
      isAutoQuality = true;
      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                      @"status": @"TRACK_CHANGED",
                                                                                      @"bitrate": [currentStreams[playIndex] objectForKey:@"bitrate"],
                                                                                      }];
   }

   currentPlayIndex = playIndex;
   metaurl = metaUrl;

   [self play:playIndex];

   self.showNowPlayingInfo = false;

   if ([options objectForKey:@"showIniOSMediaCenter"]) {
      self.showNowPlayingInfo = [[options objectForKey:@"showIniOSMediaCenter"] boolValue];
   }

   if ([options objectForKey:@"stationId"]) {
      stationFirebaseId = [options objectForKey:@"stationId"];
   }

   if ([options objectForKey:@"logo"]) {
      self.logo = [options objectForKey:@"logo"];
   }

   if (self.showNowPlayingInfo) {
      //unregister any existing registrations
      [self unregisterAudioInterruptionNotifications];
      [self unregisterRemoteControlEvents];
      //register
      [self registerAudioInterruptionNotifications];
      [self registerRemoteControlEvents];
   }

   [self setNowPlayingInfo:true];
   self.isNextPrevCmdSent = NO;
   isPlaying = true;

    playerStart = [[NSDate date] timeIntervalSince1970];
}

-(void)play:(int)playIndex {
   NSString *streamUrl = [currentStreams[playIndex] objectForKey:@"url"];
   lastPosition = 0;
   needSwitchTries = 0;
   playbackStartTime = [[NSDate date] timeIntervalSince1970];

   NSURL *url = [NSURL URLWithString:streamUrl];

   if (self.audioPlayer.state == STKAudioPlayerStatePaused && [self.lastUrlString isEqualToString:streamUrl]) {
      [self.audioPlayer resume];
   } else {
      self.dataSource = [STKAudioPlayer dataSourceFromURL:url];

      [self.audioPlayer playDataSource:self.dataSource];
   }

   self.lastUrlString = streamUrl;

   isPlaying = true;
}

RCT_EXPORT_METHOD(nowPlayInfo:(NSString *) imageURL station:(NSString *) stationName songName:(NSString*) songName) {
//   NSLog(@"setNowPlayInfo...");

   self.artWorkURL = imageURL;
   self.currentStation = stationName;
   self.currentSong = songName;
   [self setNowPlayingInfo:true];
   if(imageURL == nil) {
      return;
   }
   NSString* encoded = [self.artWorkURL stringByAddingPercentEscapesUsingEncoding:NSUTF8StringEncoding];
   NSURL *url = [NSURL URLWithString:(encoded)];
   NSURLSessionDownloadTask *downloadPhotoTask =
      [[NSURLSession sharedSession]  downloadTaskWithURL:url completionHandler:^(NSURL *location, NSURLResponse *response, NSError *error) {
            if(error == nil) {
               UIImage *downloadedImage = [UIImage imageWithData:
                                          [NSData dataWithContentsOfURL:location]];
               if(downloadedImage != nil) {
                  MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc] initWithImage: downloadedImage ];
                 self.artWork = artwork;
                 [self setNowPlayingInfo:true];
               }
            }
        }];

   // 4
   [downloadPhotoTask resume];


}

RCT_EXPORT_METHOD(seekToTime:(double) seconds)
{
   if (!self.audioPlayer) {
      return;
   }

   [self.audioPlayer seekToTime:seconds];
}

RCT_EXPORT_METHOD(goForward:(double) seconds)
{
   if (!self.audioPlayer) {
      return;
   }

   double newtime = self.audioPlayer.progress + seconds;

   if (self.audioPlayer.duration < newtime) {
      [self.audioPlayer stop];
      [self setNowPlayingInfo:false];
   } else {
      [self.audioPlayer seekToTime:newtime];
   }
}


RCT_EXPORT_METHOD(sendFireBaseEvent:(NSString*) eventName)
{
   /*
   [FIRAnalytics logEventWithName:eventName
                       parameters:@{
                                    }];
    */
}

RCT_EXPORT_METHOD(sendFireBaseEventForStation:(NSString*)stationName duration:(int)duration)
{
   /*
   [FIRAnalytics logEventWithName:@"time_listening" parameters:@{
   stationName: [NSNumber numberWithInt:duration]
   }];
   */
}

RCT_EXPORT_METHOD(goBack:(double) seconds)
{
   if (!self.audioPlayer) {
      return;
   }

   double newtime = self.audioPlayer.progress - seconds;

   if (newtime < 0) {
      [self.audioPlayer seekToTime:0.0];
   } else {
      [self.audioPlayer seekToTime:newtime];
   }
}

RCT_EXPORT_METHOD(pause)
{
   if (!self.audioPlayer) {
      return;
   } else {
      [self.audioPlayer pause];
    //  [self setNowPlayingInfo:false];
     // [self deactivate];
   }
   if(isPlaying) {
       NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
      [self sendFireBaseEventForStation:stationFirebaseId duration:(now - playerStart)];
   }

   isPlaying = false;
}

RCT_EXPORT_METHOD(resume)
{
   if (!self.audioPlayer) {
      return;
   } else {
      [self activate];
      [self.audioPlayer resume];
      [self setNowPlayingInfo:true];
      playerStart = [[NSDate date] timeIntervalSince1970];
   }

   isPlaying = true;
}

RCT_EXPORT_METHOD(stop)
{
   if (!self.audioPlayer) {
      return;
   } else {
      [self.audioPlayer stop];
      [self setNowPlayingInfo:false];
      [self deactivate];
   }

   isPlaying = false;
}



RCT_EXPORT_METHOD(getStatus: (RCTResponseSenderBlock) callback)
{
   NSString *status = @"STOPPED";
   NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
   NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];

   if (!self.audioPlayer) {
      status = @"ERROR";
   } else if ([self.audioPlayer state] == STKAudioPlayerStatePlaying) {
      status = @"PLAYING";
   } else if ([self.audioPlayer state] == STKAudioPlayerStatePaused) {
      status = @"PAUSED";
   } else if ([self.audioPlayer state] == STKAudioPlayerStateBuffering) {
      status = @"BUFFERING";
   }

   callback(@[[NSNull null], @{@"status": status, @"progress": progress, @"duration": duration, @"url": self.lastUrlString}]);
}

#pragma mark - StreamingKit Audio Player


- (void)audioPlayer:(STKAudioPlayer *)player didStartPlayingQueueItemId:(NSObject *)queueItemId
{
   NSLog(@"AudioPlayer is playing");
}

- (void)audioPlayer:(STKAudioPlayer *)player didFinishPlayingQueueItemId:(NSObject *)queueItemId withReason:(STKAudioPlayerStopReason)stopReason andProgress:(double)progress andDuration:(double)duration
{
   NSLog(@"AudioPlayer has stopped");
}

- (void)audioPlayer:(STKAudioPlayer *)player didFinishBufferingSourceWithQueueItemId:(NSObject *)queueItemId
{
   NSLog(@"AudioPlayer finished buffering");
}

- (void)audioPlayer:(STKAudioPlayer *)player unexpectedError:(STKAudioPlayerErrorCode)errorCode {
   NSLog(@"AudioPlayer unexpected Error with code %d", errorCode);
}

- (void)audioPlayer:(STKAudioPlayer *)audioPlayer didReadStreamMetadata:(NSDictionary *)dictionary {
  /* NSLog(@"ReactNativeAudioStreaming.m Received: %@", dictionary);
   NSLog(@"AudioPlayer SONG NAME  %@", dictionary[@"StreamTitle"]);

   self.currentSong = dictionary[@"StreamTitle"] ? dictionary[@"StreamTitle"] : @"";
   if ( self.currentSong.length == 0) {
     [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
         @"status": @"METADATA_UPDATED",
         @"key": @"StreamTitle",
         @"value": self.currentSong
         }];
     [self setNowPlayingInfo:true];
      return;
   }

  /* Patch for more.fm */

  /*  // \\Nas\ks\J - show the currentStation name
    NSRange kyivstar = [self.currentSong rangeOfString:@"\\\\Nas\\ks\\J"];
    NSRange lounge   = [self.currentSong rangeOfString:@"\\\\NAS\\KSLounge\\J"];
    NSRange business = [self.currentSong rangeOfString:@"\\\\NAS\\KSBusiness\\J"];

    if (( kyivstar.location != NSNotFound ) || ( lounge.location != NSNotFound ) || ( business.location != NSNotFound )) {
      // return as is, javscript will find it.
      [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                      @"status": @"METADATA_UPDATED",
                                                                                      @"key": @"StreamTitle",
                                                                                      @"value": self.currentSong
                                                                                      }];
      self.currentSong = @""; // Because Javascript set station name to nowPlayInfo and we show it always in
      // locked screen;
      [self setNowPlayingInfo:true];
      return;
    }


   NSString *stringValue = self.currentSong;
   NSArray *items = [stringValue componentsSeparatedByString:@".mp3"];
   NSString *name = items.lastObject;
   NSString *newString = [NSString stringWithFormat:@"%@",name];
   newString = [newString stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
   if (!newString.length) {
      name = items.firstObject;
   }



   name = [name stringByReplacingOccurrencesOfString:@"\\\\Nas\\st" withString:@""];
   NSRange range = [name rangeOfString:@"\\M\\Programs\\"];
   if (range.location != NSNotFound) {
     name = [name substringFromIndex:range.location + range.length];
   }
   name = [name stringByReplacingOccurrencesOfString:@"\\" withString:@""];
   if (!name.length) {
     name = items.firstObject;
   }

  //  const char *c = [name cStringUsingEncoding:NSISOLatin1StringEncoding];
  //  name = [[NSString alloc]initWithCString:c encoding:NSWindowsCP1251StringEncoding];
   name = [name stringByReplacingOccurrencesOfString:@"\t" withString:@""];
   self.currentSong = name;*/

/* End patch or more.fm */
/*
   [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                   @"status": @"METADATA_UPDATED",
                                                                                   @"key": @"StreamTitle",
                                                                                   @"value": self.currentSong
                                                                                   }];
   [self setNowPlayingInfo:true];*/
}

- (void)audioPlayer:(STKAudioPlayer *)player stateChanged:(STKAudioPlayerState)state previousState:(STKAudioPlayerState)previousState
{
   NSNumber *duration = [NSNumber numberWithFloat:self.audioPlayer.duration];
   NSNumber *progress = [NSNumber numberWithFloat:self.audioPlayer.progress];

   switch (state) {
      case STKAudioPlayerStatePlaying:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"PLAYING", @"progress": progress, @"duration": duration, @"url": self.lastUrlString}];
         break;

      case STKAudioPlayerStatePaused:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"PAUSED", @"progress": progress, @"duration": duration, @"url": self.lastUrlString}];
         break;

      case STKAudioPlayerStateStopped:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"STOPPED", @"progress": progress, @"duration": duration, @"url": self.lastUrlString}];
         break;

      case STKAudioPlayerStateBuffering:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"BUFFERING"}];
         break;

      case STKAudioPlayerStateError:
         [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent"
                                                         body:@{@"status": @"ERROR"}];
         break;

      default:
         break;
   }
}


#pragma mark - Audio Session

- (void)activate
{
   NSError *categoryError = nil;

   [[AVAudioSession sharedInstance] setActive:YES error:&categoryError];
   [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback error:&categoryError];

   if (categoryError) {
      NSLog(@"Error setting category! %@", [categoryError description]);
   }
}

- (void)deactivate
{
   NSError *categoryError = nil;

   [[AVAudioSession sharedInstance] setActive:NO error:&categoryError];

   if (categoryError) {
      NSLog(@"Error setting category! %@", [categoryError description]);
   }
}

- (void)setSharedAudioSessionCategory
{
   NSError *categoryError = nil;
   self.isPlayingWithOthers = [[AVAudioSession sharedInstance] isOtherAudioPlaying];

   [[AVAudioSession sharedInstance] setActive:NO error:&categoryError];
   [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryAmbient error:&categoryError];

   if (categoryError) {
      NSLog(@"Error setting category! %@", [categoryError description]);
   }
}

- (void)registerAudioInterruptionNotifications
{
   // Register for audio interrupt notifications
   [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(onAudioInterruption:)
                                                name:AVAudioSessionInterruptionNotification
                                              object:nil];
   // Register for route change notifications
   [[NSNotificationCenter defaultCenter] addObserver:self
                                            selector:@selector(onRouteChangeInterruption:)
                                                name:AVAudioSessionRouteChangeNotification
                                              object:nil];
}

- (void)unregisterAudioInterruptionNotifications
{
   [[NSNotificationCenter defaultCenter] removeObserver:self
                                                   name:AVAudioSessionRouteChangeNotification
                                                 object:nil];
   [[NSNotificationCenter defaultCenter] removeObserver:self
                                                   name:AVAudioSessionInterruptionNotification
                                                 object:nil];
}

- (void)onAudioInterruption:(NSNotification *)notification
{
   // Get the user info dictionary
   NSDictionary *interruptionDict = notification.userInfo;

   // Get the AVAudioSessionInterruptionTypeKey enum from the dictionary
   NSInteger interuptionType = [[interruptionDict valueForKey:AVAudioSessionInterruptionTypeKey] integerValue];

   // Decide what to do based on interruption type
   switch (interuptionType)
   {
      case AVAudioSessionInterruptionTypeBegan:
         NSLog(@"Audio Session Interruption case started.");
         [self.audioPlayer pause];
         break;

      case AVAudioSessionInterruptionTypeEnded:
         NSLog(@"Audio Session Interruption case ended.");
         if(!isPlaying) {
            break;
         }
         self.isPlayingWithOthers = [[AVAudioSession sharedInstance] isOtherAudioPlaying];
         (self.isPlayingWithOthers) ? [self.audioPlayer stop] : [self.audioPlayer resume];
         break;

      default:
         NSLog(@"Audio Session Interruption Notification case default.");
         break;
   }
}

- (void)onRouteChangeInterruption:(NSNotification *)notification
{

   NSDictionary *interruptionDict = notification.userInfo;
   NSInteger routeChangeReason = [[interruptionDict valueForKey:AVAudioSessionRouteChangeReasonKey] integerValue];

   switch (routeChangeReason)
   {
      case AVAudioSessionRouteChangeReasonUnknown:
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonUnknown");
         break;

      case AVAudioSessionRouteChangeReasonNewDeviceAvailable:
         // A user action (such as plugging in a headset) has made a preferred audio route available.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonNewDeviceAvailable");
         break;

      case AVAudioSessionRouteChangeReasonOldDeviceUnavailable:
         // The previous audio output path is no longer available.
         [self pause];
         break;

      case AVAudioSessionRouteChangeReasonCategoryChange:
         // The category of the session object changed. Also used when the session is first activated.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonCategoryChange"); //AVAudioSessionRouteChangeReasonCategoryChange
         break;

      case AVAudioSessionRouteChangeReasonOverride:
         // The output route was overridden by the app.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonOverride");
         break;

      case AVAudioSessionRouteChangeReasonWakeFromSleep:
         // The route changed when the device woke up from sleep.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonWakeFromSleep");
         break;

      case AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory:
         // The route changed because no suitable route is now available for the specified category.
         NSLog(@"routeChangeReason : AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory");
         break;
   }
}

#pragma mark - Remote Control Events

- (void)registerRemoteControlEvents
{
   MPRemoteCommandCenter *commandCenter = [MPRemoteCommandCenter sharedCommandCenter];
   [commandCenter.playCommand addTarget:self action:@selector(didReceivePlayCommand:)];
   [commandCenter.pauseCommand addTarget:self action:@selector(didReceivePauseCommand:)];
   [commandCenter.nextTrackCommand addTarget:self action:@selector(didReceiveNextTrackCommand:)];
   [commandCenter.previousTrackCommand addTarget:self action:@selector(didReceivePreviousTrackCommand:)];
   [commandCenter.togglePlayPauseCommand addTarget:self action:@selector(didReceivePlayPauseCommand:)];


   commandCenter.playCommand.enabled = YES;
   commandCenter.pauseCommand.enabled = YES;
   commandCenter.stopCommand.enabled = NO;
   commandCenter.nextTrackCommand.enabled = YES;
   commandCenter.previousTrackCommand.enabled = YES;
}

- (MPRemoteCommandHandlerStatus)didReceivePlayPauseCommand:(MPRemoteCommand *)event
{
   NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
   if(now - self.lastPlayPauseCall < 0.1) {
      return MPRemoteCommandHandlerStatusSuccess;
   }
   self.lastPlayPauseCall = now;

   [self.bridge.eventDispatcher sendDeviceEventWithName:@"AudioBridgeEvent" body:@{
                                                                                   @"status": HEADSET_PLAY,
                                                                                   }];
   return MPRemoteCommandHandlerStatusSuccess;
}


- (MPRemoteCommandHandlerStatus)didReceivePlayCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceivePlayCommand");
   [self resume];
   [self sendFireBaseEvent:@"click_play"];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceivePauseCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceivePauseCommand");
   [self pause];
   [self sendFireBaseEvent:@"click_pause"];
   return MPRemoteCommandHandlerStatusSuccess;
}

- (MPRemoteCommandHandlerStatus)didReceiveNextTrackCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceiveNextTrackCommand");
   if (self.isNextPrevCmdSent == NO) {
      self.isNextPrevCmdSent = YES;
      [self nextTrack];
   }
   return MPRemoteCommandHandlerStatusSuccess;
}


- (MPRemoteCommandHandlerStatus)didReceivePreviousTrackCommand:(MPRemoteCommand *)event
{
   NSLog(@"didReceivePreviousTrackCommand");
   if (self.isNextPrevCmdSent == NO) {
      self.isNextPrevCmdSent = YES;
      [self prevTrack];
   }
   return MPRemoteCommandHandlerStatusSuccess;
}

- (void)unregisterRemoteControlEvents
{
   MPRemoteCommandCenter *commandCenter = [MPRemoteCommandCenter sharedCommandCenter];
   [commandCenter.playCommand removeTarget:self];
   [commandCenter.pauseCommand removeTarget:self];
}

- (void)setNowPlayingInfo:(bool)isPlaying
{
   if (self.showNowPlayingInfo) {
      // TODO Get artwork from stream
      // MPMediaItemArtwork *artwork = [[MPMediaItemArtwork alloc]initWithImage:[UIImage imageNamed:@"webradio1"]];

      NSArray *array = [self.currentSong componentsSeparatedByString:@" - "];

      NSString *artist = @"";
      NSString *song = self.currentSong;

      if(array != nil && [array count] > 1) {
         artist =  [array objectAtIndex:0];
         song = [array objectAtIndex:1];
      }

      NSString* appName = [NSString stringWithFormat:@"%@ %@",@"Radio Kyivstar",self.currentStation];
      NSDictionary *nowPlayingInfo = [NSDictionary dictionaryWithObjectsAndKeys:
                                      self.currentSong, MPMediaItemPropertyAlbumTitle,
                                      artist, MPMediaItemPropertyAlbumArtist,
                                      appName ? appName : @"AppName", MPMediaItemPropertyTitle,
                                      self.artWork, MPMediaItemPropertyArtwork,
                                      [NSNumber numberWithFloat:isPlaying ? 1.0f : 0.0], MPNowPlayingInfoPropertyPlaybackRate, nil];
      [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nowPlayingInfo;
   } else {
      [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
   }
}

@end
