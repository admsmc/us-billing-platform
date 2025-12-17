local called=false
function CodeBlock(el)
  if called then return nil end
  called=true
  local r = pandoc.system.run({"bash","-lc","exit 0"})
  io.stderr:write("pandoc.system.run type="..type(r).."\n")
  if type(r)=="table" then
    for k,v in pairs(r) do
      io.stderr:write("  "..tostring(k).."="..tostring(v).." ("..type(v)..")\n")
    end
  else
    io.stderr:write("  value="..tostring(r).."\n")
  end
  return nil
end
