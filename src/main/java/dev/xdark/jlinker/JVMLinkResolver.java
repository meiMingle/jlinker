package dev.xdark.jlinker;

import java.lang.reflect.Modifier;

final class JVMLinkResolver<C, M, F> implements LinkResolver<C, M, F> {
    private final ArenaAllocator<ClassInfo<C>> classArenaAllocator;

    /**
     * @param classArenaAllocator Class arena allocator.
     */
    JVMLinkResolver(ArenaAllocator<ClassInfo<C>> classArenaAllocator) {
        this.classArenaAllocator = classArenaAllocator;
    }

    @Override
    public Result<Resolution<C, M>> resolveStaticMethod(ClassInfo<C> owner, String name, String descriptor, boolean itf) {
        Resolution<C, M> method;
        if (itf) {
            if (!Modifier.isInterface(owner.accessFlags())) {
                return Result.error(ResolutionError.CLASS_MUST_BE_INTERFACE);
            }
            method = uncachedInterfaceMethod(owner, name, descriptor);
        } else {
            method = uncachedLookupMethod(owner, name, descriptor);
        }
        if (method != null) {
            if (!Modifier.isStatic(method.member().accessFlags())) {
                return Result.error(ResolutionError.METHOD_NOT_STATIC);
            }
            if (!method.forced() && itf != Modifier.isInterface(method.owner().accessFlags())) {
                return Result.error(ResolutionError.CLASS_MUST_BE_INTERFACE);
            }
            return Result.ok(method);
        }
        return Result.error(ResolutionError.NO_SUCH_METHOD);
    }

    @Override
    public Result<Resolution<C, M>> resolveVirtualMethod(ClassInfo<C> owner, String name, String descriptor) {
        if (Modifier.isInterface(owner.accessFlags())) {
            return Result.error(ResolutionError.CLASS_MUST_NOT_BE_INTERFACE);
        }
        Resolution<C, M> method = uncachedLookupMethod(owner, name, descriptor);
        if (method == null) {
            method = uncachedInterfaceMethod(owner, name, descriptor);
        }
        if (method != null) {
            int flags = method.member().accessFlags();
            if (Modifier.isStatic(flags)) {
                return Result.error(ResolutionError.METHOD_NOT_VIRTUAL);
            }
            if (Modifier.isAbstract(flags) && !Modifier.isAbstract(method.owner().accessFlags())) {
                return Result.error(ResolutionError.CLASS_NOT_ABSTRACT);
            }
            return Result.ok(method);
        }
        return Result.error(ResolutionError.NO_SUCH_METHOD);
    }

    @Override
    public Result<Resolution<C, M>> resolveInterfaceMethod(ClassInfo<C> owner, String name, String descriptor) {
        if (!Modifier.isInterface(owner.accessFlags())) {
            return Result.error(ResolutionError.CLASS_MUST_BE_INTERFACE);
        }
        Resolution<C, M> resolution = uncachedInterfaceMethod(owner, name, descriptor);
        if (resolution == null) {
            return Result.error(ResolutionError.NO_SUCH_METHOD);
        }
        if (Modifier.isStatic(resolution.member().accessFlags())) {
            return Result.error(ResolutionError.METHOD_NOT_VIRTUAL);
        }
        return Result.ok(resolution);
    }

    @Override
    public Result<Resolution<C, F>> resolveStaticField(ClassInfo<C> owner, String name, String descriptor) {
        ClassInfo<C> info = owner;
        MemberInfo<F> field = null;
        while (owner != null) {
            field = (MemberInfo<F>) owner.getField(name, descriptor);
            if (field != null) {
                info = owner;
                break;
            }
            owner = owner.superClass();
        }
        if (field == null) {
            // Field wasn't found in super classes, iterate over all interfaces
            try (Arena<ClassInfo<C>> arena = classArenaAllocator.push()) {
                arena.push(info); // Push interface/class to the arena
                while ((info = arena.poll()) != null) {
                    if (Modifier.isInterface(info.accessFlags())) {
                        // Only check field if it's an interface.
                        field = (MemberInfo<F>) info.getField(name, descriptor);
                        if (field != null) {
                            break;
                        }
                    } else {
                        // Push super class for later check of it's interfaces too,
                        ClassInfo<C> superClass = info.superClass();
                        if (superClass != null) {
                            arena.push(superClass);
                        }
                    }
                    // Push sub-interfaces of the class
                    arena.push(info.interfaces());
                }
            }
        }
        if (field == null) {
            return Result.error(ResolutionError.NO_SUCH_FIELD);
        }
        if (!Modifier.isStatic(field.accessFlags())) {
            return Result.error(ResolutionError.FIELD_NOT_STATIC);
        }
        return Result.ok(new Resolution<>(info, field, false));
    }

    @Override
    public Result<Resolution<C, F>> resolveVirtualField(ClassInfo<C> owner, String name, String descriptor) {
        while (owner != null) {
            MemberInfo<F> field = (MemberInfo<F>) owner.getField(name, descriptor);
            if (field != null) {
                if (!Modifier.isStatic(field.accessFlags())) {
                    return Result.ok(new Resolution<>(owner, field, false));
                }
                return Result.error(ResolutionError.FIELD_NOT_VIRTUAL);
            }
            owner = owner.superClass();
        }
        return Result.error(ResolutionError.NO_SUCH_FIELD);
    }

    Resolution<C, M> uncachedLookupMethod(ClassInfo<C> owner, String name, String descriptor) {
        do {
            MemberInfo<M> member = (MemberInfo<M>) owner.getMethod(name, descriptor);
            if (member != null) {
                return new Resolution<>(owner, member, false);
            }
        } while ((owner = owner.superClass()) != null);
        return null;
    }

    Resolution<C, M> uncachedInterfaceMethod(ClassInfo<C> owner, String name, String descriptor) {
        ClassInfo<C> info = owner;
        try (Arena<ClassInfo<C>> arena = classArenaAllocator.push()) {
            arena.push(owner);
            Resolution<C, M> candidate = null;
            while ((owner = arena.poll()) != null) {
                MemberInfo<M> member = (MemberInfo<M>) owner.getMethod(name, descriptor);
                if (member != null) {
                    if (!Modifier.isAbstract(member.accessFlags())) {
                        return new Resolution<>(owner, member, false);
                    }
                    if (candidate == null) {
                        candidate = new Resolution<>(owner, member, false);
                    }
                }
                arena.push(owner.interfaces());
            }
            if (candidate != null) {
                return candidate;
            }
        }
        // We have corner case when a compiler can generate interface call
        // to java/lang/Object. This cannot happen with javac, but the spec
        // allows so.
        // TODO optimize
        info = info.superClass();
        while (info != null) {
            ClassInfo<C> superClass = info.superClass();
            if (superClass == null) {
                break;
            }
            info = superClass;
        }
        MemberInfo<M> member = (MemberInfo<M>) info.getMethod(name, descriptor);
        if (member != null) {
            int accessFlags = member.accessFlags();
            if (Modifier.isStatic(accessFlags) || !Modifier.isPublic(accessFlags)) {
                member = null;
            }
        }
        return member == null ? null : new Resolution<>(info, member, true);
    }
}