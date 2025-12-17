-- Pandoc Lua filter: render ```mermaid code blocks to images via Mermaid CLI (mmdc).
--
-- Usage:
--   pandoc docs/product-spec.md \
--     --lua-filter scripts/pandoc/mermaid.lua \
--     -o docs/product-spec.pdf

local out_dir = os.getenv("MERMAID_OUT_DIR") or "build/mermaid"
local scale = os.getenv("MERMAID_SCALE") or "2"

local counter = 0

local function ensure_dir(path)
  -- pandoc.system.make_directory(path, true) exists in newer pandoc.
  if pandoc and pandoc.system and pandoc.system.make_directory then
    pandoc.system.make_directory(path, true)
    return
  end
  os.execute(string.format("mkdir -p %q", path))
end

local function run_cmd(cmd)
  -- Lua 5.2+ returns (ok, how, code) for os.execute.
  local ok, how, code = os.execute(cmd)
  if type(ok) == "number" then
    -- Lua 5.1 compatibility (ok is status code)
    return ok
  end
  if ok == true and (how == "exit" or how == nil) then
    return code or 0
  end
  return 1
end

ensure_dir(out_dir)

function CodeBlock(el)
  if not el.classes:includes("mermaid") then
    return nil
  end

  counter = counter + 1
  local in_file = string.format("%s/diagram-%03d.mmd", out_dir, counter)
  local out_file = string.format("%s/diagram-%03d.png", out_dir, counter)

  local f = io.open(in_file, "w")
  if f == nil then
    return nil
  end
  f:write(el.text)
  f:close()

  -- Print-friendly rendering:
  -- - light theme
  -- - white background
  -- - scale up for PDF legibility
  local cmd = string.format(
    "mmdc -i %q -o %q --theme neutral --backgroundColor white --scale %s",
    in_file,
    out_file,
    tostring(scale)
  )

  local exit_code = run_cmd(cmd)
  if exit_code ~= 0 then
    -- If rendering failed, leave the original block in place.
    return nil
  end

  -- Replace the code block with an image.
  -- width=100% typically maps to \\linewidth in LaTeX output.
  local attr = pandoc.Attr("", {}, { { "width", "100%" } })

  -- Pandoc 3.x constructor: Image(caption, src, title, attr)
  local caption = {}
  local title = ""
  return pandoc.Para({ pandoc.Image(caption, out_file, title, attr) })
end
