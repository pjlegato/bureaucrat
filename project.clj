(defproject bureaucrat "0.2.0"
  :description "MQ-based API router"
  :url "https://github.com/pjlegato/bureaucrat/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :jvm-opts ["-d64" "-Duser.timezone=GMT" "-server" "-Djava.awt.headless=true" "-Dfile.encoding=utf-8"
             "-Xmx1024m" "-Xms1024m" 
             "-XX:+UseConcMarkSweepGC" "-XX:+CMSIncrementalMode" "-XX:+PrintGCDetails" "-XX:+PrintGCTimeStamps"
             ]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 
                 ;; Codecs
                 [org.clojure/data.codec "0.1.0"] ;; Base64
                 [cheshire "5.3.1"] ;; JSON

                 ;; Language utilities
                 [org.tobereplaced/mapply "1.0.0"] ;; Apply for maps / keyword arguments
                 [slingshot "0.10.3"] ;; Extended try/catch
                 [clj-time "0.6.0"]
                 [com.stuartsierra/component "0.2.1"]  ;; Software components framework
                 [org.clojure/math.numeric-tower "0.0.4"]

                 [org.clojure/core.async "0.1.298.0-2a82a1-alpha"]
                 [com.climate/claypoole "0.2.1"]  ;; Thread pool management
                 [org.clojure/tools.reader "0.8.4"] ;; Safe EDN parser
                 [onelog "0.4.3"]  ;; Logging library
                 [environ "0.4.0"] ;; Read config values from env vars

                 [org.clojars.pjlegato/clansi   "1.3.0"] ;; ANSI colorization
                 [org.clojars.pjlegato/lock-key "1.1.0-SNAPSHOT"] ;; Wrapper around Java cryptography extensions
                 [environ "0.4.0"]  ;; Get config from environment

                 ;; IronMQ client
                 [io.iron.ironmq/ironmq "0.0.14"] ;; IronMQ Java client library (the Clojure one 
                                                  ;; is woefully out of date)

                 ;; HornetQ client
                 [org.immutant/immutant-messaging "1.1.1"]

                 ;; HTTP client / server
                 [http-kit "2.1.16"]
                 ]
  :profiles {:dev {:dependencies [[alembic "0.2.1"]  ;; Reload project.clj without JVM restart
                                  [midje "1.6.3"     ;; Testing framework.
                                   :exclusions [dynapath]] ;; Midje's dynapath dependency conflicts with Immutant's newer one.
                                  ]}}
  :global-vars {*warn-on-reflection* true}
  :immutant {:nrepl-port 7654
             :context-path "/"})
