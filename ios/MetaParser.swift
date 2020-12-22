import UIKit

@objc(MetaParser)
class MetaParser: NSObject, URLSessionDataDelegate {
      @objc var lastSongName: String!
      @objc var lastArtUrl: String!
      @objc var changed = false
      var session: URLSession!
      var dataTask: URLSessionDataTask!
      
      // Implement methods that you want to export to the native module
      @objc func fetch(_ src: String) {
         let UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/27.0.1453.110 Safari/537.36"
         
         if dataTask != nil {
            dataTask.cancel()
         }
         
         let config = URLSessionConfiguration.default
         config.requestCachePolicy = .reloadIgnoringLocalCacheData
         config.urlCache = nil
         
         session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
         //let session = URLSession.shared
         if  let url = URL(string: src) {
            // print("URL created: \(url)")
            var mutableRequest = URLRequest(url: url)
            mutableRequest.setValue("1", forHTTPHeaderField: "Icy-MetaData")
            mutableRequest.setValue(UA, forHTTPHeaderField: "User-Agent")
            dataTask = session.dataTask(with: mutableRequest)
            
            // print(dataTask)
            dataTask.resume()
            //print(dataTask)
         }
      }
      
      func parseData (_ data: Data ) -> Bool {
         var found: Bool = false
         
         if let rcvd = String (data: data, encoding: .isoLatin1) {
            if let range = rcvd.range(of: "StreamTitle='") {
               let start = rcvd[range.upperBound...]
               if let finishIndex = start.range(of: "';", options: .backwards)?.lowerBound {
                  let resultLatin = start[..<finishIndex]
                  let cString = resultLatin.cString(using: .isoLatin1)
                  
                  let result = String(cString: cString!, encoding: .utf8)
                  print("result" + result!)
                  
                  if self.lastSongName != result {
                     self.changed = true
                     self.fetchArtWork(songName: result)
                  }
                  self.lastSongName = result
                  
                  found = true
               }
            }
            if let range = rcvd.range(of: "image\":\"") {
               let start = rcvd[range.upperBound...]
               if let finishIndex = start.range(of: "\"")?.lowerBound {
                  let resultLatin = start[..<finishIndex]
                  let cString = resultLatin.cString(using: .isoLatin1)
                  
                  let result = String(cString: cString!, encoding: .utf8)
                  print("image " + result!)
                  
                  if result == "nocover.png" {
                     self.lastArtUrl = nil
                  } else {
                     self.lastArtUrl = "https://more.fm/admin/playlist_img/" + (result?.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed))!
                  }
                  found = true
               }
            }
         }
         
         if ( found ) {
            return true
         }
         
         return false
      }
   
      func fetchArtWork(songName: String!) {
         let src = "https://more.fm/send-track?track_name=" + songName.addingPercentEncoding(withAllowedCharacters: NSCharacterSet.urlQueryAllowed)!
         
         let config = URLSessionConfiguration.default
         config.requestCachePolicy = .reloadIgnoringLocalCacheData
         config.urlCache = nil
         session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
         //let session = URLSession.shared
         if  let url = URL(string: src) {
            // print("URL created: \(url)")
            var mutableRequest = URLRequest(url: url)
            dataTask = session.dataTask(with: mutableRequest)
            
            // print(dataTask)
            dataTask.resume()
            //print(dataTask)
         }
      }
   
      func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
         if let rcvd = String (data: data, encoding: .isoLatin1) {
            print("String length:\(rcvd.count)")
            if rcvd.range(of: "StreamTitle=") != nil {
               print("StreamTitle")
               if self.parseData(data) {
                  session.invalidateAndCancel()
               }
            }
            if rcvd.range(of: "image\":") != nil {
               if self.parseData(data) {
                  session.invalidateAndCancel()
               }
            }
         }
      }
}
