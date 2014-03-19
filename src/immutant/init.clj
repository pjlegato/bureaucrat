(ns immutant.init
  (:require [onelog.core :as log]))

(log/info "[immutant.init] Loading...")

;; Add a default JVM-wide uncaught exception handler, for background threads
(def default-exception-handler (Thread/setDefaultUncaughtExceptionHandler
                                (reify Thread$UncaughtExceptionHandler
                                  (uncaughtException [this thread throwable]
                                    (log/error "Default JVM exception logger: got an uncaught exception from " thread "!" 
                                               (log/throwable throwable))))))

(log/info "[immutant.init] Done.")
