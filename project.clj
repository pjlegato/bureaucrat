(defproject bureaucrat "0.1.0-SNAPSHOT"
  :description "MQ-based API router"
  :url "https://github.com/pjlegato/bureaucrat/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0-RC1"]

                 [com.stuartsierra/component "0.2.1"]  ;; Software components framework
                 [org.clojars.pjlegato/clansi "1.3.0"] ;; ANSI colorization
                 [onelog "0.4.3"]  ;; Logging library
                 [environ "0.4.0"] ;; Read config values from env vars
                 [cheshire "5.3.1"] ;; JSON parser
                 [org.tobereplaced/mapply "1.0.0"] ;; Apply for maps / keyword arguments

                 [io.iron.ironmq/ironmq "0.0.14"] ;; IronMQ Java client library (the Clojure one 
                                                  ;; is woefully out of date)
                 [http-kit "2.1.16"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [com.climate/claypoole "0.2.1"]  ;; Thread pool management
                 ]
  :profiles {:dev {:dependencies [[midje "1.6.3"]]}}
  :immutant {:nrepl-port 7654
             :context-path "/"})
