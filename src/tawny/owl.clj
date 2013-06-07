;; The contents of this file are subject to the LGPL License, Version 3.0.

;; Copyright (C) 2012, 2013, Newcastle University

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Lesser General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Lesser General Public License for more details.

;; You should have received a copy of the GNU Lesser General Public License
;; along with this program.  If not, see http://www.gnu.org/licenses/.

(ns ^{:doc "Build ontologies in OWL."
      :author "Phillip Lord"}
    tawny.owl
  (:require
   [clojure.walk :only postwalk]
   [tawny.util :as util])
  (:import
   (org.semanticweb.owlapi.model OWLOntologyManager OWLOntology IRI
                                 OWLClassExpression OWLClass OWLAnnotation
                                 OWLNamedObject OWLOntologyID
                                 OWLAnnotationProperty OWLObjectProperty
                                 OWLDataProperty
                                 )
   (org.semanticweb.owlapi.apibinding OWLManager)
   (org.coode.owlapi.manchesterowlsyntax ManchesterOWLSyntaxOntologyFormat)
   (org.semanticweb.owlapi.io StreamDocumentTarget OWLXMLOntologyFormat
                              RDFXMLOntologyFormat)
   (org.semanticweb.owlapi.util DefaultPrefixManager OWLEntityRemover)
   (java.io ByteArrayOutputStream FileOutputStream PrintWriter)
   (java.io File)
   (java.util Collections)
   (org.semanticweb.owlapi.model AddAxiom RemoveAxiom AddImport
                                 AddOntologyAnnotation)))



;; The next three forms all contain values that percolate across all the
;; namespaces that contain ontologies. Resetting all of these when owl.clj is
;; eval'd is a pain, hence they are all defonce.
(defonce
  ^{:doc "A java object which is the main factory for all other objects"
    }
  ontology-data-factory
  (OWLManager/getOWLDataFactory))

(defonce
  ^{:doc "The single OWLOntologyManager used by tawny."}
  owl-ontology-manager
  (OWLManager/createOWLOntologyManager ontology-data-factory))

(defonce
  ^{:doc "Map between namespaces and ontologies"}
  ontology-for-namespace (ref {}))

;; the current ontology provides our main mutable state. Strictly, we don't
;; need to do this, but the alternative would be passing in an ontology to
;; almost every call, or running everything inside a binding. Painful.
(def
  ^{:dynamic true
    :doc "The currently bound ontology. If this is not set, then the current
ontology is expected to be bound to the current namespace with 'defontology'
or similar macros. Normally, when set, this is set with the 'with-ontology'
macro." }
  *current-bound-ontology* nil)

(defn named-object?
  "Returns true iff entity is an OWLNamedObject."
  [entity]
  (instance? OWLNamedObject entity))

(defn as-named-object
  "If entity is a named object do nothing, else throw
an exception."
  [entity]
  (or
   (and (instance? OWLNamedObject entity)
        entity)
   (throw (IllegalArgumentException. "Expecting a named entity"))))

(def
  ^{:doc "Hook called immediately after an ontology is removed from the
owl-ontology-manager."}
  remove-ontology-hook (util/make-hook))

(defn remove-ontology-maybe
  "Removes the ontology with the given ID from the manager.
This calls the relevant hooks, so is better than direct use of the OWL API. "
  [ontologyid]
  (when (.contains owl-ontology-manager ontologyid)
    (let [ontology (.getOntology owl-ontology-manager ontologyid)]
      (.removeOntology
       owl-ontology-manager ontology)
      (util/run-hook remove-ontology-hook ontology))))


(declare add-annotation)
(declare owlcomment)
(declare versioninfo)
(declare ontology-options)
(defn ontology
  "Returns a new ontology. See 'defontology' for full description."
  [& args]
  (let [options (apply hash-map args)
        iri (IRI/create (:iri options))]
    (remove-ontology-maybe
     (OWLOntologyID. iri))

    (let [jontology
          (.createOntology owl-ontology-manager iri)

          ontology-format
          (.getOntologyFormat
           owl-ontology-manager jontology)]

      ;; iri gen options needs to be remembered
      (when-let [iri-gen (:iri-gen options)]
        (dosync
         (alter (ontology-options jontology)
                merge {:iri-gen iri-gen})))

      ;; put prefix into the prefix manager
      ;; this isn't a given -- the prefix manager being returned by default
      ;; returns true for isPrefixOWLOntologyFormat, so we can do this, but
      ;; strictly we are depending on the underlying types.
      (when-let [prefix (:prefix options)]
        (.setPrefix ontology-format
                    prefix
                    (.toString iri)))


      ;; add annotations to the ontology
      (add-annotation
       jontology
       (filter (comp not nil?)
               (flatten
                (list
                 (:annotation options)
                 (when-let [c (:comment options)]
                   (owlcomment c))
                 (when-let [v (:versioninfo options)]
                   (versioninfo v))))))
      jontology)))

;;
;; we use intern-owl here because we need to attach the ontology to the
;; metadata for the symbol. With def this isn't possible because we need
;; to generate the symbol at compile time, when we don't have the
;; ontology yet. &form is an implicit variable for macro's, and carries
;; the line and column metadata which otherwise we loose. This is a
;; compiler detail, but there is no other way, as far as I can tell.
;;
;; However, we still need to declare at this point, or we get into
;; trouble in macros such as "as-disjoint" which check symbols at compile
;; time, and cannot determine that the symbol will exist at run time,
;; once the intern has run.
;;
;; This all seems horribly messy, and I am not sure that it is all true;
;; after all I can do this?
;; (let [n (tawny.owl/owlclass "y")]
;;                 (def ^{:tmp n} g n))
;;
;; However, an attempt to try this using this sort of thing fails: the
;; metadata disappears away, I think because the reader is doing something
;; special with it. Regardless it does not appear in the expansion.
;;
;; (def ^{:owl true
;;        :doc
;;        (tawny.util.CallbackString.
;;         (partial tawny.repl/fetch-doc ontology#))}
;;   ~ontname ontology#)
;;
;; Also have tried calling the "ontology" function at compile time, and then
;; generating the appropriate form.
;;
;; This sounds reasonable, but doesn't work either, because valn# is the FORM
;; and not the evaluation of it.
;;
;; (defmacro defthing
;;   [name val]
;;   (let [valn# (identity val)]
;;     `(def
;;        ~(vary-meta
;;          name
;;          merge
;;          {:owl true
;;           :val valn#})
;;        ~valn#)
;;      ))
;;
;; So, I think that this is about the best I can do. The ultimate solution has
;; to be changes to the way the doc lookup works. Putting an entity into it's
;; own metadata makes no sense. It would be much cleaner if the doc function
;; checked the *value* of a var, to see if it implements a documentation
;; protocol, and if not uses the :doc metadata.
(defmacro defontology
  "Define a new ontology with `name'.

The following keys must be supplied.
:iri -- the IRI for the new ontology
:prefix -- the prefix used in the serialised version of the ontology
"
  [name & body]
  `(do
     (let [ontology# (ontology ~@body)]
       (def
         ~(with-meta name
            (assoc (meta name)
              :owl true))
         ontology#)
       (tawny.owl/ontology-to-namespace ontology#)
       ontology#
       )))

(defn ontology-to-namespace
  "Sets the current ontology as defined by `defontology'"
  [ontology]
  (dosync (ref-set
           ontology-for-namespace
           (merge @ontology-for-namespace
                  {*ns* ontology}))))


;; ontology options -- additional knowledge that I want to attach to each
;; ontology,  but which gets junked when the ontology does.
(def ^{:doc "Ontology options. A map on a ref for each ontology"}
  ontology-options-atom (atom {}))

(defmacro defontfn
  "Like defn but creates adds an arity one less than 'body', which
defers to 'body' but adds the current-ontology as an argument.
"
  [symbol & body]
  (let
      ;; prematter is doc string and attr-map
      [prematter (take-while (complement sequential?) body)
       fnbody (drop-while (complement sequential?) body)
       arglist (first fnbody)
       butfirstarglist (drop 1 arglist)
       bodynoargs (rest fnbody)
       no-ont-body
       `(~(vec butfirstarglist)
           (~symbol (tawny.owl/get-current-ontology) ~@butfirstarglist))]
    (when (not (vector? arglist))
      (throw
       (IllegalArgumentException. "Can't deal with multiple arity functions yet")))
    `(defn ~symbol
       ~@prematter
       ~no-ont-body ~fnbody)))

;; basically, this does the same thing as defontfn, but is easier I think.
;; bind to
(defmacro with-ontology
  "Sets the default ontology for all operations inside its dynamic scope."
 [ontology & body]
  `(binding [tawny.owl/*current-bound-ontology* ~ontology]
     ~@body))

(defn ontology-first-maybe
  "Given a function f, returns a function which if the first arg is an
OWLOntology sets it as the current ontology, then calls f with the remain
args, or else calls f."
  [f]
  (fn ontology-first-maybe
    [& args]
    (if (and
         ;; balk if nil
         (seq args)
         ;; ontology first
         (instance? OWLOntology
                        (first args)))
     (with-ontology (first args)
        (apply f (rest args)))
      (apply f args))))

(def
  ^{:doc "Transform a two arg function so that if the first element is an
  ontology set it as the current, then drop this parameter. Then call the next
  parameter repeatedly against all the remaining parameters."}
  ontology-vectorize
  (comp ontology-first-maybe util/vectorize))


(declare get-current-ontology)

;; return options for ontology -- lazy (defn get-ontology-options [ontology])
(defontfn ontology-options
  "Returns the ontology options for 'ontology'
or the current-ontology"
  [ontology]
  (if-let [options
           (get @ontology-options-atom ontology)]
    options
    (get
     (swap!
      ontology-options-atom assoc ontology (ref {}))
     ontology)))

(util/add-hook remove-ontology-hook
               (fn [ontology]
                 (dosync
                  (swap! ontology-options-atom
                         dissoc ontology))))

(def
  ^{:doc "Return the entities for a given IRI. One or more ontologies can
  searched. If imports is false do not "
    :arglists '([iri & ontology] [iri imports & ontology])
    }
  entity-for-iri
  (fn entity-for-iri
    [iri & ontology]))

(defn test-ontology
  "Define a test ontology.

This function probably shouldn't be here, but one of the consequences of
making the ontology implicit in all my functions is that playing on the repl
is a pain, as the test ontology has to be defined first.

This defines a minimal test ontology.

"
  []
  (defontology a-test-ontology :iri "http://iri/" :prefix "test:"))

(defn get-current-ontology
  "Gets the current ontology"
  ([]
     (get-current-ontology *ns*))
  ([ns]
     ;; if current ontology is inside a binding
     (or *current-bound-ontology*
         ;; so use the namespace bound one
         (get @ontology-for-namespace ns)
         ;; so break
         (throw (IllegalStateException. "Current ontology has not been set")))))


(defontfn get-iri
  "Gets the IRI for the given ontology, or the current ontology if none is given"
  [ontology]
  (.getOntologyIRI
   (.getOntologyID ontology)))

(defn get-current-iri[]
  "DEPRECATED: Use 'get-iri' instead. "
  {:deprecated "0.8"}
  (get-iri))

(defontfn get-prefix
  "Returns the prefix for the given ontology, or the current ontology if none
is given."
  [ontology]
  ;; my assumption here is that there will only ever be one prefix for a given
  ;; ontology. If not, it's all going to go wrong.
  (first
   (keys
    (.getPrefixName2PrefixMap
     (.getOntologyFormat owl-ontology-manager
                         ontology)))))

(defn get-current-prefix []
  "Gets the current prefix"
  {:deprecated "0.8"}
  (get-prefix))

(defn save-ontology
  "Save the current ontology in the file returned by `get-current-file'.
or `filename' if given.
"
  ([filename]
     (save-ontology filename (ManchesterOWLSyntaxOntologyFormat.)
                    (str "## This file was created by Clojure-OWL\n"
                         "## It should not be edited by hand\n" )))
  ([filename format]
     (save-ontology filename format ""))
  ([filename format prepend]
     (let [file (new File filename)
           output-stream (new FileOutputStream file)
           file-writer (new PrintWriter output-stream)
           existingformat (.getOntologyFormat owl-ontology-manager
                                              (get-current-ontology))
           this-format
           (cond
            (= format :rdf) (RDFXMLOntologyFormat.)
            (= format :omn) (ManchesterOWLSyntaxOntologyFormat.)
            (= format :owl) (OWLXMLOntologyFormat.)
            :else format)]
       (when (.isPrefixOWLOntologyFormat this-format)
         (dorun
          (map #(.setPrefix this-format (get-prefix %)
                            (str (.toString (get-iri %)) "#"))
               (vals @ontology-for-namespace))))
       (.print file-writer prepend)
       (.flush file-writer)
       (.setPrefix this-format (get-current-prefix)
                   (str (.toString (get-current-iri)) "#"))
       (.saveOntology owl-ontology-manager (get-current-ontology)
                      this-format output-stream))))

(defontfn iriforname
  "Returns an IRI object for the given name.

This is likely to become a property of the ontology at a later date, but at
the moment it is very simple."
  [ontology name]
  (if-let [iri-gen (:iri-gen (deref (ontology-options ontology)))]
    (iri-gen name)
    (IRI/create (str (get-iri ontology) "#" name))))

(defn- get-create-object-property
  "Creates an OWLObjectProperty for the given name."
  [name]
  (.getOWLObjectProperty ontology-data-factory
                         (iriforname name)))

(defn- ensure-object-property
  "Ensures that the entity in question is an OWLObjectProperty
or throw an exception if it cannot be converted."
  [prop]
  (cond
   (fn? prop)
   (ensure-object-property (prop))
   (instance? OWLObjectProperty prop)
   prop
   (string? prop) 
  (get-create-object-property prop)
   true
   (throw (IllegalArgumentException.
           (str "Expecting an object property. Got: " prop)))))

(defn- get-create-class
  "Returns an OWL class."
  [name]
  (.getOWLClass ontology-data-factory
                (iriforname name)))

(defn ensure-class [clz]
  "If clz is a String return a class of with that name,
else if clz is a OWLClassExpression add that."
  (cond
   (fn? clz)
   (ensure-class (clz))
   (instance? org.semanticweb.owlapi.model.OWLClassExpression clz)
   clz
   (string? clz)
   (get-create-class clz)
   true
   (throw (IllegalArgumentException.
           (str "Expecting a class. Got: " clz)))))

(defontfn add-axiom
  "Adds an axiom from the given ontology, or the current one."
  [ontology axiom]
  (.applyChange owl-ontology-manager
                (AddAxiom. ontology axiom))
  axiom)

(defontfn remove-axiom
  "Removes an axiom from the given ontology, or the current one."
  [ontology axiom]
  (.applyChange owl-ontology-manager
                (RemoveAxiom. ontology axiom))
  axiom)

(defontfn remove-entity
  "Remove from the ontology an entity created and added by
owlclass, defclass, objectproperty or defoproperty. Entity is the value
returned by these functions.

This removes all the axioms that were added. So, for example, a form such as

   (defclass a
      :subclass b
      :equivalent c)

adds three axioms -- it declares a, makes it a subclass of b, and equivalent
of c."
  [ontology entity]
  (let [remover
        (OWLEntityRemover. owl-ontology-manager
                           (hash-set
                            (get-current-ontology)))]
    (.accept entity remover)
    (.applyChanges owl-ontology-manager
                   (.getChanges remover))))

(defn- add-one-frame
  "Adds a single frame to the ontology.

OWL isn't actually frame based, even if Manchester syntax is. My original
intention is that this would be suitable for adding frame in to the ontology
but in practice this doesn't work, as not everything is an axiom.
"
  [ontology frame-adder name frame]
  (let [clazz (ensure-class name)
        axiom (frame-adder clazz frame)]
    (add-axiom ontology axiom)
    axiom))

(defn- add-frame
"Adds frames with multiple objects to the ontology"
  [ontology frame-adder name frame]
  (doall
   (map (fn[x]
          (add-one-frame ontology frame-adder name x))
        ;; owlsome, only, someonly return lists
        ;; nil check as all the "add-subclass" like vars take nil and do
        ;; nothing.
        (filter (comp not nil?)
                (flatten frame)))))


(defn- create-subclass-axiom
  "Creates a subclass axiom for the given class and subclass.

The class needs to be a OWLClass object, while the subclass can be a string,
class, or class expression. "
  [clazz subclass]
  (.getOWLSubClassOfAxiom
   ontology-data-factory
   clazz
   (ensure-class subclass)))

(def
  ^{:doc "Adds a specific class to the ontology"
    :arglists '([name & subclass] [ontology name & subclass])}
  add-subclass
  (ontology-first-maybe
   (fn [name & subclass]
     (add-frame (get-current-ontology)
                create-subclass-axiom
                name
                subclass))))

(defn- create-equivalent-axiom
  "Creates an equivalent axiom."
  [clazz equivalent]
  (.getOWLEquivalentClassesAxiom
   ontology-data-factory
   clazz
   (ensure-class equivalent)))

(defontfn add-equivalent
  "Adds an equivalent axiom to the ontology."
  [ontology name equivalent]
  {:pre [(or (nil? equivalent)
             (seq? equivalent))]}
  (add-frame ontology create-equivalent-axiom name equivalent))

(defn- create-disjoint-axiom
  "Creates a disjoint axiom."
  [clazz disjoint]
  (.getOWLDisjointClassesAxiom
   ontology-data-factory
   (into-array OWLClassExpression [clazz disjoint])))

(defontfn add-disjoint
  "Adds a disjoint axiom to the ontology."
  [ontology name disjoint]
  {:pre [(or (nil? disjoint)
             (seq? disjoint))]}
  (add-frame ontology create-disjoint-axiom name disjoint))

(defn- create-class-axiom
  "Returns a declaration axiom"
  [clazz _]
  (.getOWLDeclarationAxiom
   ontology-data-factory
   clazz))

(defontfn add-disjoint-union
  "Adds a disjoint union axiom to all subclasses."
  [ontology clazz subclasses]
  (let [ensured-subclasses
        (doall (map #(ensure-class %) subclasses))
        ]
    (list
     (add-axiom
      (.getOWLDisjointUnionAxiom
       ontology-data-factory
       (ensure-class clazz)
       (java.util.HashSet.  ensured-subclasses))))))

(defontfn add-class
  "Adds a class to the ontology."
  [ontology name]
  (add-one-frame ontology create-class-axiom name ""))

(defontfn add-domain
  "Adds all the entities in domainlist as domains to a property."
  [ontology property domainlist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [domain]
        (add-axiom ontology
         (.getOWLObjectPropertyDomainAxiom
          ontology-data-factory property
          (ensure-class domain))))
      domainlist))))

(defontfn add-range
  "Adds all the entities in rangelist as range to a property."
  [ontology property rangelist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [range]
        (add-axiom ontology
         (.getOWLObjectPropertyRangeAxiom
          ontology-data-factory property
          (ensure-class range))))
      rangelist))))

(defontfn add-inverse
  "Adds all the entities in inverselist as inverses to a property."
  [ontology property inverselist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [inverse]
        (add-axiom ontology
         (.getOWLInverseObjectPropertiesAxiom
          ontology-data-factory property
          (ensure-object-property inverse))))
      inverselist))))


(defontfn add-superproperty
  "Adds all items in superpropertylist to property as
a superproperty."
  [ontology property superpropertylist]
  (let [property (ensure-object-property property)]
    (doall
     (map
      (fn [superproperty]
        (add-axiom ontology
         (.getOWLSubObjectPropertyOfAxiom
          ontology-data-factory property
          (ensure-object-property superproperty))))
      superpropertylist))))


;; Really it would make more sense to use keywords, but this breaks the
;; groupify function which expects alternative keyword value args. The
;; approach of using strings and symbol names here is scary -- if someone does
;; (defclass transitive) for example, it's all going to break. I don't think
;; that the const does what it might
(def ^:const transitive "transitive")
(def ^:const functional "functional")
(def ^:const inversefunctional "inversefunctional")

(def
  ^{:private true}
  charfuncs
  {transitive #(.getOWLTransitiveObjectPropertyAxiom %1 %2)
   functional #(.getOWLFunctionalObjectPropertyAxiom %1 %2)
   inversefunctional #(.getOWLInverseFunctionalObjectPropertyAxiom %1 %2)
   })

(defontfn add-characteristics
  "Add a list of characteristics to the property."
  [ontology property characteristics]
  (doall
   (map
    (fn [x]
      (when-not (get charfuncs x)
        (throw (IllegalArgumentException.
                "Characteristic is not recognised:" x)))
      (add-axiom ontology
       ((get charfuncs x)
        ontology-data-factory (ensure-object-property property))))
    characteristics)))

(def
  ^{:doc "Frames to add to all new classes."
    :dynamic true
    :private true}
  *default-frames* nil)

(def
  ^{:doc "Axioms we have added recently"
    :dynamic true}
  recent-axiom-list
  nil)

;; object properties
(defn objectproperty-explicit
  "Returns an objectproperty. This requires an hash with a list
value for each frame."
  [name {:keys [domain range inverseof subpropertyof characteristics] :as all}]
  (let [property (ensure-object-property name)
        axioms
        (concat
         (list (add-axiom
                (.getOWLDeclarationAxiom
                 ontology-data-factory property)))
         (add-domain property domain)
         (add-range property range)
         (add-inverse property inverseof)
         (add-superproperty property subpropertyof)
         (add-characteristics property characteristics)
         )]
    ;; store classes if we are in an inverse binding
    (when (seq? recent-axiom-list)
      (set! recent-axiom-list
            (concat (list property) recent-axiom-list)))
    property))


(defn objectproperty
  "Returns a new object property in the current ontology."
  [name & frames]
  (objectproperty-explicit
   name
   (util/check-keys
    (merge-with concat
                (util/hashify frames)
                *default-frames*)
    [:domain :range :inverseof :subpropertyof :characteristics])))

(defmacro defoproperty
  "Defines a new object property in the current ontology."
  [property & frames]
  `(let [property-name# (name '~property)
         property# (tawny.owl/objectproperty property-name# ~@frames)]
     (def ~(with-meta property
             (assoc (meta name)
               :owl true))
       property#)))

;; restrictions! name clash -- we can do nothing about this, so accept the
;; inconsistency and bung owl on the front.
(def
  ^{:doc "Returns an OWL some values from restriction."
    :arglists '([property & clazzes] [ontology property & clazzes])}
  owlsome
  (ontology-vectorize
   (fn owlsome [property class]
     (.getOWLObjectSomeValuesFrom
      ontology-data-factory
      (ensure-object-property property)
      (ensure-class class)))))

(def
  ^{:doc "Returns an OWL all values from restriction."
    :arglists '([property & clazzes] [ontology property & clazzes])}
  only
  (ontology-vectorize
   (fn onlymore [property class]
      (.getOWLObjectAllValuesFrom
       ontology-data-factory
       (ensure-object-property property)
       (ensure-class class)))))

;; long shortcut -- for consistency with some
(def owlonly only)


;; forward declaration
(declare owlor)
(defn someonly
  "Returns an restriction combines the OWL some values from and
all values from restrictions."
  [property & classes]
  (list
   (apply
    owlsome
    (concat
     (list property) classes))

   (only property
         (apply owlor classes))))


;; union, intersection
(defn owland
  "Returns an OWL intersection of restriction."
  [& classes]
  (let [classes (flatten classes)]
    (when (> 1 (count classes))
      (throw (IllegalArgumentException. "owland must have at least two classes")))

    (.getOWLObjectIntersectionOf
     ontology-data-factory
     (java.util.HashSet.
      (doall (map
              #(ensure-class %)
              ;; flatten list for things like owlsome which return lists
              classes))))))

;; short cuts for the terminally lazy. Still prefix!
(def && owland)

(defn owlor
  "Returns an OWL union of restriction."
  [& classes]
  (let [classes (flatten classes)]
    (when (> 1 (count classes))
      (throw (IllegalArgumentException. "owlor must have at least two classes")))

    (.getOWLObjectUnionOf
     ontology-data-factory
     (java.util.HashSet.
      (doall (map #(ensure-class %)
                  (flatten classes)))))))

(def || owlor)

;; lots of restrictions return a list which can be of size one. so all these
;; functions take a list but ensure that it is of size one.
(defn owlnot
  "Returns an OWL complement of restriction."
  [& class]
  {:pre [(= 1
            (count (flatten class)))]}
  (.getOWLObjectComplementOf
   ontology-data-factory
   (ensure-class (first (flatten class)))))

(def ! owlnot)

;; cardinality
(defn atleast
  "Returns an OWL atleast cardinality restriction."
  [cardinality property & class]
  {:pre [(= 1
            (count (flatten class)))]}
  (.getOWLObjectMinCardinality
   ontology-data-factory cardinality
   (ensure-object-property property)
   (ensure-class (first (flatten class)))))

(defn atmost
  "Returns an OWL atmost cardinality restriction."
  [cardinality property & class]
  {:pre [(= 1
            (count (flatten class)))]}
  (.getOWLObjectMaxCardinality
   ontology-data-factory cardinality
   (ensure-object-property property)
   (ensure-class (first (flatten class)))))

(defn exactly
  "Returns an OWL exact cardinality restriction."
  [cardinality property & class]
  {:pre [(= 1
            (count (flatten class)))]}
  (.getOWLObjectExactCardinality
   ontology-data-factory cardinality
   (ensure-object-property property)
   (ensure-class (first (flatten class)))))

(declare ensure-individual)
(defn oneof
  "Returns an OWL one of property restriction."
  [& individuals]
  (.getOWLObjectOneOf
   ontology-data-factory
   (java.util.HashSet.
    (doall
     (map #(ensure-individual %)
          (flatten individuals))))))

;; annotations
(defn- add-a-simple-annotation
  [ontology named-entity annotation]
  (let [axiom
        (.getOWLAnnotationAssertionAxiom
         ontology-data-factory
         (.getIRI named-entity) annotation)]
    (add-axiom axiom)))

(defn- add-an-ontology-annotation
  [ontology annotation]
  (.applyChange
   owl-ontology-manager
   (AddOntologyAnnotation. ontology annotation)))

(defn add-annotation
  ([ontology named-entity annotation-list]
     (doall
      (for [n annotation-list]
        (add-a-simple-annotation ontology named-entity n))))
  ([ontology-or-named-entity annotation-list]
     (doall
      (for [n annotation-list]
        (cond
         (instance? OWLOntology ontology-or-named-entity)
         (add-an-ontology-annotation ontology-or-named-entity n)
         (instance? OWLNamedObject ontology-or-named-entity)
         (add-a-simple-annotation
          (get-current-ontology)
          ontology-or-named-entity n))))))

(defn- ensure-annotation-property [property]
  "Ensures that 'property' is an annotation property,
converting it from a string or IRI if necessary."
  (cond
   (instance? OWLAnnotationProperty property)
   property
   (instance? IRI property)
   (.getOWLAnnotationProperty
    ontology-data-factory property)
   (instance? String property)
   (ensure-annotation-property
    (iriforname property))
   :default 
  (throw (IllegalArgumentException.
           (format "Expecting an OWL annotation property: %s" property)))))

(defn annotation
  "Creates a new annotation property."
  ([annotation-property literal]
     (annotation annotation-property literal "en")) 
 ([annotation-property literal language]
     (.getOWLAnnotation
      ontology-data-factory
      annotation-property
      (.getOWLLiteral ontology-data-factory literal language))))

(defn- add-a-super-annotation
  "Adds a single superproperty to subproperty."
  [ontology subproperty superproperty]
  (.applyChange owl-ontology-manager
   (AddAxiom.
    ontology
    (.getOWLSubAnnotationPropertyOfAxiom
     ontology-data-factory
     subproperty
     (ensure-annotation-property superproperty)))))

(defontfn add-super-annotation
  "Adds a set of superproperties to the given subproperty."
  [ontology subproperty superpropertylist]
  (doall
   (map #(add-a-super-annotation ontology subproperty %1)
        superpropertylist)))


;; various annotation types
(def labelproperty
  (.getRDFSLabel ontology-data-factory))

(def label
  (partial annotation labelproperty))

(def owlcommentproperty
  (.getRDFSComment ontology-data-factory))

(def owlcomment
  (partial annotation owlcommentproperty))

(def isdefinedbyproperty
  (.getRDFSIsDefinedBy ontology-data-factory))

(def isdefinedby
  (partial annotation isdefinedbyproperty))

(def seealsoproperty
  (.getRDFSSeeAlso ontology-data-factory))

(def seealso
  (partial annotation seealsoproperty))

(def backwardcompatiblewithproperty
  (.getOWLBackwardCompatibleWith ontology-data-factory))

(def backwardcompatiblewith
  (partial annotation backwardcompatiblewithproperty))

(def incompatiblewithproperty (.getOWLIncompatibleWith ontology-data-factory))

(def incompatiblewith
  (partial annotation incompatiblewithproperty))

(def versioninfoproperty
  (.getOWLVersionInfo ontology-data-factory))

(def versioninfo
  (partial annotation versioninfoproperty))

(defn annotation-property-explicit
  "Add this annotation property to the ontology"
  [property frames]
  (let [property-object
        (ensure-annotation-property property)
        ontology (get frames :ontology (get-current-ontology))
        ]
    ;; add the property
    (.addAxiom owl-ontology-manager
               ontology
               (.getOWLDeclarationAxiom
                ontology-data-factory
                property-object))

    (when (:comment frames)
      (add-annotation ontology
                      property-object
                      (list (owlcomment
                             (first (:comment frames))))))

    (when (:label frames)
      (add-annotation ontology
                      property-object
                      (list (label
                             (first
                              (:label frames))))))

    (when-let [supers (:subproperty frames)]
      (add-super-annotation ontology
       property-object supers))

    (add-annotation ontology property-object (:annotation frames))
    property-object))

(defn annotation-property
  "Creates a new annotation property."
  [name & frames]
  (annotation-property-explicit
   name
   (util/check-keys
    (util/hashify frames)
    [:annotation :label :comment :subproperty]))
)
(defn- get-annotation-property
  "Gets an annotation property with the given name."
  [property]
  (.getOWLAnnotationProperty
   ontology-data-factory
   (iriforname property)))

(defmacro defannotationproperty
  "Defines a new annotation property in the current ontology.
See 'defclass' for more details on the syntax."
  [property & frames]
  `(let [property-name# (name '~property)
         property#
         (tawny.owl/annotation-property property-name# ~@frames)]
     (def
       ~(vary-meta property
                   merge
                   {:owl true})
       property#)))

(defn owlclass-explicit
  "Creates a class in the current ontology.
Frames is a map, keyed on the frame name, value a list of items (of other
lists) containing classes. This function has a rigid syntax, and the more
flexible 'owlclass' is normally prefered. However, this function should be
slightly faster.
"
  ([name frames]
     (let [classname (or (first (:name frames)) name)
           class
           (ensure-class classname)]
       ;; store classes if we are in a disjoint binding
       (when (seq? recent-axiom-list)
         (set! recent-axiom-list
               (concat (list class)
                       recent-axiom-list)))
       ;; create the class
       (do
         ;; add-class returns a single axiom -- concat balks at this
         (add-class class)
         (add-subclass class (:subclass frames))
         (add-equivalent class (:equivalent frames))
         (add-disjoint class (:disjoint frames))
         (add-annotation class (:annotation frames))

         ;; change these to add to the annotation frame instead perhaps?
         (when (:comment frames)
           (add-annotation class
                           (list (owlcomment
                                  (first (:comment frames))))))

         (when (:label frames)
           (add-annotation class
                           (list (label
                                  (first
                                   (:label frames))))))
         ;; return the class object
         class)))
  ([name]
     (owlclass-explicit name {})))


(defn owlclass
  "Creates a new class in the current ontology. See 'defclass' for
full details."
  ([name & frames]
     (owlclass-explicit
      name
      (util/check-keys
       (merge-with
               concat
               (util/hashify frames)
               *default-frames*)
       [:subclass :equivalent :annotation
        :name :comment :label :disjoint]))))

(defmacro defclass
  "Define a new class. Accepts a set number of frames, each marked
by a keyword :subclass, :equivalent, :annotation, :name, :comment,
:label or :disjoint. Each frame can contain an item, a list of items or any
combination of the two. The class object is stored in a var called classname."
  [classname & frames]
  `(let [string-name# (name '~classname)
         class# (tawny.owl/owlclass string-name# ~@frames)]
     (def
       ~(vary-meta classname
                   merge
                   {:owl true})
       class#)))


(defn disjointclasseslist
  "Makes all elements in list disjoint.
All arguments must of an instance of OWLClassExpression"
  [list]
  {:pre (seq? list)}
  (let [classlist
        (doall
         (map
          (fn [x]
            (ensure-class x))
          list))]
    (add-axiom
     (.getOWLDisjointClassesAxiom
      ontology-data-factory
      (into-array OWLClassExpression
                  classlist)))))

(defn disjointclasses
  "Makes all the arguments disjoint.
All arguments must be an instance of OWLClassExpression."
  [& list]
  (disjointclasseslist list))

(defn- get-create-individual
  "Returns an individual for the given name."
  [individual]
  (.getOWLNamedIndividual ontology-data-factory
                          (iriforname individual)))

(defn- ensure-individual [individual]
  "Returns an INDIVIDUAL.
If INDIVIDUAL is an OWLIndividual return individual, else
interpret this as a string and create a new OWLIndividual."
  (cond (instance? org.semanticweb.owlapi.model.OWLIndividual)
        individual
        (string? individual)
        (get-create-individual individual)
        true
        (throw (IllegalArgumentException.
                (str "Expecting an Individual. Got: " individual)))))

(def
  ^{:doc "Adds CLAZZES as a type to individual to current ontology
or ONTOLOGY if present."
    :arglists '([individual & clazzes] [ontology individual & clazzes])}
  add-type
  (ontology-vectorize
   (fn add-type [individual clazz]
     (add-axiom
      (.getOWLClassAssertionAxiom
       ontology-data-factory
       (ensure-class clazz)
       individual)))))

(def add-fact ^{:doc "Add FACTS to an INDIVIDUAL in the current ontology or
  ONTOLOGY if present. Facts are produced with `fact' and `fact-not'."
    :arglists '([individual & facts] [ontology individual & facts]) }
  (ontology-vectorize
   (fn add-fact [individual fact]
     (add-axiom
      (fact individual)))))

(defn fact
  "Returns a fact asserting a relationship with PROPERTY toward an
individual TO."
  [property to]
  (fn fact [from]
    (.getOWLObjectPropertyAssertionAxiom
     ontology-data-factory
     property from to)))

(defn fact-not
  "Returns a fact asserting the lack of a relationship along PROPERTY
toward an individual TO."
  [property to]
  (fn fact-not [from]
    (.getOWLNegativeObjectPropertyAssertionAxiom
     ontology-data-factory
     property from to)))

(def
  ^{:doc "Adds all arguments as the same individual to the current ontology
or to ONTOLOGY if present."
    :arglists '([ontology & individuals] [& individuals])}
  add-same
  (ontology-first-maybe
   (fn add-same [& args]
     (add-axiom
      (.getOWLSameIndividualAxiom
       ontology-data-factory
       (set (flatten args)))))))

(def
  ^{:doc "Adds all arguments as different individuals to the current
  ontology unless first arg is an ontology in which case this is used"}
  add-different
  (ontology-first-maybe
   (fn add-different [& args]
     (add-axiom
      (.getOWLDifferentIndividualsAxiom
       ontology-data-factory
       (set (flatten args)))))))

;; need to support all the different frames here...
;; need to use hashify
(defn individual
  "Returns a new individual."
  [name & frames]
  (let [hframes
        (util/check-keys
         (util/hashify frames)
         [:type :fact :same :different])
        individual (ensure-individual name)]
    (when (:type hframes)
      (add-type individual (:type hframes)))
    (when (:fact hframes)
      (add-fact individual (:fact hframes)))
    (when (:same hframes)
      (add-same individual (:same hframes)))
    (when (:different hframes)
      (add-different individual (:different hframes)))
    individual))


(defmacro defindividual
  "Declare a new individual."
  [individualname & frames]
  `(let [string-name# (name '~individualname)
         individual# (tawny.owl/individual string-name# ~@frames)]
     (def
       ~(vary-meta individualname
                  merge
                  {:owl true})
       individual#)))

(load "owl_data")

;; owl imports
(defn owlimport
  "Adds a new import to the current ontology."
  ([ontology]
     (owlimport (get-current-ontology) ontology))
  ([ontology-into ontology]
     (.applyChange owl-ontology-manager
                   (AddImport. ontology-into
                               (.getOWLImportsDeclaration
                                ontology-data-factory
                                (get-iri ontology))))))
;; convienience macros
;; is this necessary? is as-disjoint-subclasses not enough?
(defmacro as-disjoint [& body]
  "All entities declared in scope are declared as disjoint.
See also 'as-subclasses'."
  `(do ;; delete all recent classes
     (binding [tawny.owl/recent-axiom-list '()]
       ;; do the body
       ~@body
       ;; set them disjoint if there is more than one. if there is only one
       ;; then it would be illegal OWL2. this macro then just shields the body
       ;; from any other as-disjoint statements.
       (when (< 1 (count tawny.owl/recent-axiom-list))
         (tawny.owl/disjointclasseslist
          tawny.owl/recent-axiom-list)))))

(defmacro as-inverse [& body]
  "The two properties declared in the dynamic scope of this macro
are declared as inverses."
  `(do
     (binding [tawny.owl/recent-axiom-list '()]
       ~@body
       (when-not (= (count tawny.owl/recent-axiom-list) 2)
         (throw
          (IllegalArgumentException.
           "Can only have two properties in as-inverse")))
       (tawny.owl/add-inverse
        (first tawny.owl/recent-axiom-list)
        (rest tawny.owl/recent-axiom-list))
       )))


;; specify default frames which should be merged with additional frames passed
;; in. place into a dynamic variable and then use (merge-with concat) to do
;; the business
(defmacro with-default-frames [frames & body]
  "Adds a standard frame to all entities declared within its scope.
This macro is lexically scoped."
  `(binding [tawny.owl/*default-frames*
             (tawny.util/hashify ~frames)]
     ~@body))


(defmacro as-disjoint-subclasses
  "All declared subclasses in body. Convienience
macro over 'as-subclasses'"
  [superclass & body]
  `(as-subclasses ~superclass :disjoint ~@body))

(defmacro as-subclasses [superclass & body]
  "All classes defined within body are given a superclass.
The first items in body can also be options.

:disjoint also sets the class disjoint.
:cover also makes the subclasses cover the superclass.

This macro is dynamically scoped."
  (let [options# (vec (take-while keyword? body))
        rest# (drop-while keyword? body)]
    `(binding [tawny.owl/recent-axiom-list '()]
       (with-default-frames [:subclass ~superclass]
        ~@rest#)
       (#'tawny.owl/subclass-options
        ~options#
        ~superclass
        tawny.owl/recent-axiom-list))))

(defn- subclass-options
  "Handles disjoint and covering axioms on subclasses."
  [options superclass subclasses]
  (let [optset (into #{} options)]
    (when (and
           (contains? optset :disjoint)
           ;; set them disjoint if there is more than one. if there is only one
           ;; then it would be illegal OWL2. this macro then just shields the body
           ;; from any other as-disjoint statements.
           (< 1 (count tawny.owl/recent-axiom-list)))
      (disjointclasseslist
       recent-axiom-list))
    (when (and
           (contains? optset :cover))
      (add-equivalent
       superclass
       (list (owlor recent-axiom-list))))))

(defmacro declare-classes
  "Declares all the classes given in names.

This is mostly useful for forward declarations, but the classes declared will
have any default frames or disjoints if `as-disjoints' or
`with-default-frames' or equivalent macros are in use.

See `defclassn' to define many classes with frames.
"
  [& names]
  `(do ~@(map
          (fn [x#]
            `(defclass ~x#))
          names)))

(defmacro defclassn
  "Defines many classes at once.

Each class and associated frames should be supplied as a vector.

See `declare-classes' where frames (or just default frames) are not needed.
"
  [& classes]
  `(do ~@(map
          (fn [x#]
            `(defclass ~@x#)) classes)))

;; predicates
(defontfn direct-superclasses
  "Returns the direct superclasses of name.
Name can be either a class or a string name. Returns a list of class
expressions."
  [ontology name]
  (let [clz (ensure-class name)]
    ;; general Class expressions return empty
    (if (instance? OWLClass clz)
      (.getSuperClasses clz
                        ontology)
      ())))

;; does the OWL API really not do this for me?
(defn- superclasses-1
  "Returns all subclasses of all classes in classlist."
  [ontology classlist]
  ;; if there are no subclasses return empty list
  (if (= 0 (count classlist))
    (list)
    (concat (list (first classlist))
            ;; can't use recur, not in tail position
            (superclasses-1 ontology
                          (rest classlist))
            (superclasses-1 ontology
                          (direct-superclasses ontology (first classlist))))))

(defontfn superclasses [ontology class]
  "Return all subclasses of class"
  (superclasses-1 ontology (direct-superclasses ontology class)))

(defontfn superclass?
  "Returns true is name has superclass as a superclass"
  [ontology name superclass]
  (let [namecls (ensure-class name)
        superclasscls (ensure-class superclass)]
    (some #(.equals superclasscls %) (superclasses ontology name))))

(defontfn direct-subclasses
  "Returns the direct subclasses of name."
  [ontology name]
  (let [clz (ensure-class name)]
    (if (instance? OWLClass clz)
      (.getSubClasses (ensure-class name)
                      ontology)
      ())))

(declare subclasses)
(defontfn subclass?
  "Returns true if name has subclass as a subclass"
  [ontology name subclass]
  (let [namecls (ensure-class name)
        subclasscls (ensure-class subclass)]
    (some #(.equals subclasscls %) (subclasses ontology name))))

(defn- subclasses-1
  "Returns all subclasses of all classes in classlist."
  [ontology classlist]
  ;; if there are no subclasses return empty list
  (if (= 0 (count classlist))
    (list)
    (concat (list (first classlist))
            ;; can't use recur, not in tail position
            (subclasses-1 ontology
                          (rest classlist))
            (subclasses-1 ontology
                          (direct-subclasses ontology (first classlist))))))

(defontfn subclasses [ontology class]
  "Return all subclasses of class"
  (subclasses-1 ontology (direct-subclasses ontology class)))

(defontfn disjoint?
  "Returns t iff classes are asserted to be disjoint."
  [ontology a b]
  (contains?
   (.getDisjointClasses a ontology)
   b))


(defontfn equivalent?
  "Returns t iff classes are asserted to be equivalent."
  [ontology a b]
  (contains?
   (.getEquivalentClasses a ontology)
   b))


;; some test useful macros

;; modified from with-open
(defmacro with-probe-entities
  "Evaluate the body with a number of entities defined. Then
delete these entities from the ontology"
  [bindings & body]
  (when-not (vector? bindings)
    (IllegalArgumentException. "with-probe-entities requires a vector"))
  (when-not (even? (count bindings))
    (IllegalArgumentException.
     "with-probe-entities requires an even number of forms in binding vector"))
  (cond
   (= (count bindings) 0)
   `(do ~@body)
   (symbol? (bindings 0))
   `(let ~(subvec bindings 0 2)
      (with-probe-entities
        ~(subvec bindings 2)
        ;; try block just so we can use finally
        (try
          ~@body
          (finally
            (tawny.owl/remove-entity ~(bindings 0))))))
   :else
   (throw (IllegalArgumentException.
           "with-probe-entities only allows Symbols in bindings"))))


(defmacro with-probe-axioms
  "Evaluate the body with a number of axioms. Then
delete these axioms from the ontology.

This is mostly useful for test cases. Axioms can be added, consistency
or inconsistency can be checked then removed, leaving the ontology
effectively unchanged."
  [bindings & body]
  (when-not (vector? bindings)
    (IllegalArgumentException. "with-probe-axioms requires a vector"))
  (when-not (even? (count bindings))
    (IllegalArgumentException.
     "with-probe-axioms requires an even number of forms in binding vector"))
  (cond
   (= (count bindings) 0)
   `(do ~@body)
   (symbol? (bindings 0))
   `(let ~(subvec bindings 0 2)
      (with-probe-axioms
        ~(subvec bindings 2)
        ;; try block just so we can use finally
        (try
          ~@body
          (finally
            (tawny.owl/remove-axiom ~(bindings 0))))))
   :else
   (throw (IllegalArgumentException.
           "with-probe-axioms only allows Symbols in bindings"))))

(defn owlthing
  "Object representing OWL thing."
  []
  (.getOWLThing ontology-data-factory))

(defn owlnothing
  "Object representing OWL nothing."
  []
  (.getOWLNothing ontology-data-factory))

;; add a prefix or suffix to contained defclass
(defn- alter-symbol-after-def-form
  "Searches for a defclass form, then changes the symbol by applying f."
  [f x]
  (cond
   (and (seq? x)
        (= (first x) 'defclass))
   `(defclass ~(f (second x))
      ~@(drop 2 x))
   :default
   x))

(defn- prefix-symbol [prefix sym]
  "Add a prefix to a symbol and return a new symbol."
  (symbol
   (str prefix (name sym))))

(defn- suffix-symbol
  "Add a suffix to a symbol and return a new symbol"
  [suffix sym]
  (symbol
   (str (name sym) suffix)))

(defn- alter-all-symbol-after-def-form
  "Walk over forms and applies function
f to the symbol after a defclass"
  [f x]
  (clojure.walk/postwalk
   (partial alter-symbol-after-def-form f)
   x
   ))

(defmacro with-prefix
  "Adds a prefix to all defclass macros in scope.
This is a convienience macro and is lexically scoped."
  [prefix & body]
  (let [newbody
        (alter-all-symbol-after-def-form
         (partial prefix-symbol prefix)
         body)]
    `(do ~@newbody)))


(defmacro with-suffix
  "Adds a suffix to all defclass macros in scope.
This is a convienience macro and is lexically scoped."
  [suffix & body]
  (let [newbody
        (alter-all-symbol-after-def-form
         (partial suffix-symbol suffix)
         body)]
    `(do ~@newbody)))


(defmulti refine
  "Takes an existing definition, adds it to the current ontology, and then
adds more frames. owlentity is the OWLEntity to be refined, and frames are the
additional frames. The keys to the frames must be appropriate for the type of
entity. See 'owlclass' or 'objectproperty' for more details.

This is useful for two main reasons. First, to build class definitions in two
places and add frames in both of these places. For simple forward declaration
'declare-classes' is better. The second is where the same class needs to
appear in two ontologies, but with more axioms in the second. This can enable,
for example, building two interlocking ontologies with different OWL profiles.
"
  (fn [owlentity & frames] (class owlentity)))

(defmethod refine OWLClass [& args]
  (apply owlclass args))

(defmethod refine OWLObjectProperty [& args]
  (apply objectproperty args))

(defmacro defrefineto
  "Takes an existing definition, add more frames.
The first argument should be a symbol that will hold the

See also 'refine'.
"
  [symbol & args]
  `(def
     ~(with-meta symbol
        (assoc (meta symbol)
          :owl true))
     (tawny.owl/refine ~@args)))

(defmacro defrefine
  "Takes an existing definition, add more frames.

The first element should be a namespace qualified symbol. The
unqualifed part of this will be used in the current namespace.

See also 'refine'
"
  [symb & args]
  (let [newsymbol#
        (symbol (name symb))]
    `(def
       ~(with-meta newsymbol#
          (assoc (meta newsymbol#)
            :owl true))
       (tawny.owl/refine ~symb ~@args))))


(defmacro defcopy
  "Takes an existing definition from another namespace and copies it into the
current namespace with no changes in semantics. This can be useful for
convienience, where one namespace should contain all the OWLObjects of
another, or for forward declaration, where entities will be refined later.

This does not add the existing definition to the current ontology. In most
cases this will have been imported."
  [symb & args]
  (let [newsymbol#
        (symbol (name symb))]
    `(def
       ~(with-meta newsymbol#
          (assoc (meta newsymbol#)
            :owl true))
       (var-get (var ~symb)))))
