(defproject bureaucrat "0.1.0-SNAPSHOT"
  :description "MQ-based API router"
  :url "https://github.com/pjlegato/bureaucrat/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0-beta2"]

                 [org.clojars.pjlegato/clansi "1.3.0"] ;; ANSI colorization
                 [alembic "0.2.1"] ;; Allows dynamic reloading of dependencies
                 [onelog "0.4.3"]  ;; Logging library
                 [environ "0.4.0"] ;; Read config values from env vars
                 [cheshire "5.3.1"] ;; JSON parser
                 ]

  :immutant {:nrepl-port 7654
             :context-path "/"})
