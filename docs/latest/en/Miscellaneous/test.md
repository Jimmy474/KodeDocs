---
title: Dynamic Registry
description: A guide to creating your own dynamic registry with the Fabric API.
authors:
  - Jimmy474
author-nogithub:
  - Jimmy474
---

This page explains what a dynamic registry is, why it is useful, and how to create and use one with the Fabric API.

## General Information {#general-information}

### Why Are They Useful? {#why-are-they-useful}

1. They separate your logic from your content.
2. 
## Creating a Dynamic Registry {#creating-a-dynamic-registry}

### Class Setup {#class-setup}

First, create the class that represents one registry entry.

```kodedocs
file: ../app/src/main/resources/test.java
```

```kodedocs
file: ../app/src/main/resources/test.java
include: main
```

```kodedocs
file: ../app/src/main/resources/test.java
exclude: temp
```

```kodedocs
file: ../app/src/main/resources/test.java
include: main
exclude: temp
```

### Registering the Registry {#registering-the-registry}

First, Create a key for the registry

#### Using `register()` {#using-register}

`DynamicRegistries.register()` creates a non-synced registry. It is loaded only on the server and is not available on the client.

::: info

The repeated `example-mod/example-mod` is not a mistake.

The first `example-mod` is the namespace of the entry being added. The second `example-mod` comes from the registry ID itself. Using your mod ID for both is normal, and it still allows other mods or data packs to add entries to your registry under their own namespace.

:::