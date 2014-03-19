(ns com.paullegato.bureaucrat.test-helpers
  "Utility functions for use in tests.")


(defn spin-on 
  "Spins at most n times waiting for fn to be true. Each spin is
  timeout ms long."
  ([fn] (spin-on fn 20 200))
  ([fn n timeout]
     (Thread/sleep timeout)
     (if (and (not (fn))
              (> n 0))
       (recur fn (- n 1) timeout))))
